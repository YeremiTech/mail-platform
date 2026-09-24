package com.yeremitech.mailplatform.api.service;

import com.yeremitech.mailplatform.application.model.MailBatchData;
import com.yeremitech.mailplatform.application.port.MailBatchRepository;
import com.yeremitech.mailplatform.application.usecase.CreateBatchUseCase;
import com.yeremitech.mailplatform.application.usecase.QueueEmailUseCase;
import com.yeremitech.mailplatform.domain.BatchStatus;
import com.yeremitech.mailplatform.domain.EmailAddress;
import com.yeremitech.mailplatform.domain.MailPriority;
import com.yeremitech.mailplatform.domain.MailRecipient;
import com.yeremitech.mailplatform.domain.RecipientType;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Stages up to 10k recipients; a scheduled drainer commits at most 100 emails per transaction. */
@Service
public class CampaignService {
    private final NamedParameterJdbcTemplate jdbc;
    private final JsonMapper json;
    private final MailBatchRepository batches;
    private final QueueEmailUseCase queue;
    private final Clock clock;
    private final SuppressionService suppressions;
    private final UnsubscribeLinkService unsubscribeLinks;
    private final int drainSize;

    public CampaignService(NamedParameterJdbcTemplate jdbc, JsonMapper json, MailBatchRepository batches,
            QueueEmailUseCase queue, Clock clock, SuppressionService suppressions,
            UnsubscribeLinkService unsubscribeLinks,
            @Value("${app.campaign.drain-size:100}") int drainSize) {
        if (drainSize < 1 || drainSize > 100) throw new IllegalArgumentException("campaign drain-size must be 1..100");
        this.jdbc=jdbc; this.json=json; this.batches=batches; this.queue=queue; this.clock=clock; this.suppressions=suppressions; this.unsubscribeLinks=unsubscribeLinks; this.drainSize=drainSize;
    }

    /** Preserves v0.6's default: a caller never implicitly creates a marketing campaign. */
    @Transactional
    public Submission stage(String clientId, String subject, String pinnedTemplate,
            Map<String,Object> commonVariables, List<UUID> attachmentIds, List<CreateBatchUseCase.Recipient> recipients) {
        return stage(clientId,subject,pinnedTemplate,commonVariables,attachmentIds,recipients,
                "TRANSACTIONAL",Set.of());
    }

    @Transactional
    public Submission stage(String clientId, String subject, String pinnedTemplate,
            Map<String,Object> commonVariables, List<UUID> attachmentIds, List<CreateBatchUseCase.Recipient> recipients,
            String purpose,Set<String> consentedAddresses) {
        if (!"TRANSACTIONAL".equals(purpose) && !"MARKETING".equals(purpose))
            throw new IllegalArgumentException("purpose must be TRANSACTIONAL or MARKETING");
        if ("MARKETING".equals(purpose)) {
            unsubscribeLinks.requireMarketingConfiguration();
            if (consentedAddresses == null) throw new IllegalArgumentException("marketing consent is required");
        }
        if (recipients == null || recipients.isEmpty() || recipients.size() > 10000) {
            throw new IllegalArgumentException("campaign must contain 1 to 10000 recipients");
        }
        if (subject == null || subject.isBlank() || subject.length()>300 || subject.contains("\n") || subject.contains("\r")) {
            throw new IllegalArgumentException("invalid subject");
        }
        // Validate the entire campaign before creating any persistent rows.
        Set<String> addresses = new HashSet<>();
        for (var person : recipients) {
            if (person == null) throw new IllegalArgumentException("campaign recipient is missing");
            String address = new EmailAddress(person.email()).value();
            if (!addresses.add(address)) throw new IllegalArgumentException("campaign contains repeated email addresses");
            if ("MARKETING".equals(purpose) && !consentedAddresses.contains(address))
                throw new IllegalArgumentException("marketing requires explicit consent for every recipient");
            if (person.displayName() != null && person.displayName().length() > 200) {
                throw new IllegalArgumentException("recipient display name exceeds 200 characters");
            }
            if (person.variables() != null && json.writeValueAsBytes(person.variables()).length > 8192) {
                throw new IllegalArgumentException("recipient variables exceed 8 KB");
            }
        }
        if (commonVariables != null && json.writeValueAsBytes(commonVariables).length > 16384) {
            throw new IllegalArgumentException("common variables exceed 16 KB");
        }
        if ("MARKETING".equals(purpose)) {
            Boolean registered=jdbc.queryForObject("""
                    select exists(select 1 from mail_api_client where client_id=:client and enabled=true)
                    """,Map.of("client",clientId),Boolean.class);
            if (!Boolean.TRUE.equals(registered))
                throw new IllegalArgumentException("marketing requires an active, persistently provisioned client");
        }
        Set<String> blocked = "MARKETING".equals(purpose)
                ? suppressions.findSuppressed(clientId,List.copyOf(addresses)) : Set.of();
        UUID id=UUID.randomUUID();
        Instant now=clock.instant();
        batches.save(new MailBatchData(id, clientId, subject, pinnedTemplate, recipients.size()-blocked.size(), BatchStatus.QUEUED, now, now));
        jdbc.update("update mail_batch set purpose=:purpose where id=:id",Map.of("purpose",purpose,"id",id));
        for (UUID attachment : attachmentIds == null ? List.<UUID>of() : attachmentIds) {
            if (jdbc.update("""
                    insert into mail_campaign_attachment(batch_id, attachment_id)
                    select :batch, id from mail_attachment where id=:attachment and client_id=:client
                    """, Map.of("batch",id, "attachment",attachment,"client",clientId)) != 1) {
                throw new IllegalArgumentException("attachment not owned by client");
            }
        }
        Map<String,Object> shared=commonVariables == null ? Map.of() : commonVariables;
        int suppressedCount=0;
        for (int i=0;i<recipients.size();i++) {
            var person=recipients.get(i);
            // Fail at submission, not hours later during worker processing.
            new EmailAddress(person.email());
            boolean suppressed=blocked.contains(new EmailAddress(person.email()).value());
            if(suppressed) suppressedCount++;
            Map<String,Object> vars=new HashMap<>(shared);
            if (person.variables()!=null) vars.putAll(person.variables());
            try {
                jdbc.update("""
                        insert into mail_campaign_recipient(batch_id, position, email, display_name, variables_json, state, suppressed_at)
                        values (:batch,:position,:email,:name,:vars,:state,
                                case when :state='SUPPRESSED' then current_timestamp else null end)
                        """, new MapSqlParameterSource().addValue("batch",id).addValue("position",i)
                        .addValue("email",new EmailAddress(person.email()).value()).addValue("name",person.displayName())
                        .addValue("vars",json.writeValueAsString(vars))
                        .addValue("state",suppressed?"SUPPRESSED":"PENDING"));
            } catch (Exception ex) { throw new IllegalArgumentException("invalid campaign recipient data", ex); }
        }
        return new Submission(id, recipients.size()-suppressedCount,suppressedCount,"STAGED");
    }

    /** Database row locking prevents competing workers and cancellation from racing on the same campaign. */
    @Transactional
    public int drainOneChunk() {
        List<UUID> selected=jdbc.query("""
                select b.id from mail_batch b
                where b.status <> 'CANCELLED' and (b.next_drain_at is null or b.next_drain_at <= current_timestamp)
                    and exists (
                    select 1 from mail_campaign_recipient r where r.batch_id=b.id and r.state='PENDING'
                )
                order by b.created_at for update skip locked limit 1
                """, new MapSqlParameterSource(), (rs,row)->rs.getObject(1,UUID.class));
        if (selected.isEmpty()) return 0;
        UUID batchId=selected.getFirst();
        MailBatchData batch=batches.findById(batchId).orElseThrow();
        List<Pending> pending=jdbc.query("""
                select position, email, display_name, variables_json from mail_campaign_recipient
                where batch_id=:id and state='PENDING' order by position
                limit :limit for update skip locked
                """, Map.of("id",batchId,"limit",drainSize), (rs,row) -> new Pending(
                rs.getInt(1),rs.getString(2),rs.getString(3),rs.getString(4)));
        boolean marketing = Boolean.TRUE.equals(jdbc.queryForObject(
                "select purpose='MARKETING' from mail_batch where id=:id",Map.of("id",batchId),Boolean.class));
        Set<String> blocked = marketing ? suppressions.findSuppressed(batch.clientId(),
                pending.stream().map(p->p.email()).toList()) : Set.of();
        List<UUID> attachments=jdbc.query("select attachment_id from mail_campaign_attachment where batch_id=:id",
                Map.of("id",batchId),(rs,row)->rs.getObject(1,UUID.class));
        int suppressedDuringDrain=0;
        for (Pending person:pending) {
            if(marketing && blocked.contains(new EmailAddress(person.email()).value())) {
                suppressedDuringDrain++;
                jdbc.update("""
                        update mail_campaign_recipient set state='SUPPRESSED',suppressed_at=current_timestamp
                        where batch_id=:id and position=:position and state='PENDING'
                        """,Map.of("id",batchId,"position",person.position()));
                continue;
            }
            Map<String,Object> vars;
            try { vars=new HashMap<>(json.readValue(person.variablesJson(), new TypeReference<Map<String,Object>>(){})); }
            catch (Exception ex) { throw new IllegalStateException("persisted campaign variables are invalid", ex); }
            if(marketing) {
                // Override any user-supplied value; the URL cannot be injected by a CSV column.
                vars.put("unsubscribeUrl",unsubscribeLinks.issue(batch.clientId(),person.email()));
            }
            try {
            queue.execute(new QueueEmailUseCase.Command(batch.clientId(),
                    "campaign:"+batchId+":"+person.position(), batch.subject(),batch.templateKey(),
                    vars, Map.of(),null,
                    List.of(new MailRecipient(new EmailAddress(person.email()),RecipientType.TO,person.displayName())),
                    attachments,MailPriority.BULK,batchId));
            } catch (com.yeremitech.mailplatform.application.error.RateLimitExceededException ex) {
                throw new CampaignQuotaExceededException(batchId);
            }
            jdbc.update("""
                    update mail_campaign_recipient set state='SUBMITTED', submitted_at=current_timestamp
                    where batch_id=:id and position=:position and state='PENDING'
                    """,Map.of("id",batchId,"position",person.position()));
        }
        if(suppressedDuringDrain>0) {
            jdbc.update("""
                    update mail_batch set total_recipients=greatest(0,total_recipients-:suppressed),
                        updated_at=current_timestamp where id=:id
                    """,Map.of("id",batchId,"suppressed",suppressedDuringDrain));
        }
        return pending.size();
    }

    /** Called from a separate transaction after the failed drain chunk is rolled back. */
    @Transactional
    public void deferUntilNextUtcDay(UUID batchId) {
        Instant tomorrow=java.time.LocalDate.now(java.time.ZoneOffset.UTC).plusDays(1)
                .atStartOfDay(java.time.ZoneOffset.UTC).toInstant().plusSeconds(10);
        jdbc.update("update mail_batch set next_drain_at=:next where id=:id and status<>'CANCELLED'",
                Map.of("next",java.sql.Timestamp.from(tomorrow),"id",batchId));
    }

    public static final class CampaignQuotaExceededException extends RuntimeException {
        private final UUID batchId;
        public CampaignQuotaExceededException(UUID batchId) {
            super("campaign client daily mail quota has been reached"); this.batchId=batchId;
        }
        public UUID batchId(){return batchId;}
    }

    public int stagedRemaining(String clientId,UUID batchId) {
        Integer count=jdbc.queryForObject("""
                select count(*) from mail_campaign_recipient r join mail_batch b on b.id=r.batch_id
                where b.client_id=:client and r.batch_id=:id and r.state='PENDING'
                """,Map.of("id",batchId,"client",clientId),Integer.class);
        return count==null?0:count;
    }

    public int suppressedCount(String clientId,UUID batchId) {
        Integer count=jdbc.queryForObject("""
                select count(*) from mail_campaign_recipient r join mail_batch b on b.id=r.batch_id
                where b.client_id=:client and r.batch_id=:id and r.state='SUPPRESSED'
                """,Map.of("id",batchId,"client",clientId),Integer.class);
        return count==null?0:count;
    }
    public record Submission(UUID batchId,int stagedRecipients,int suppressedRecipients,String status) {}
    private record Pending(int position,String email,String displayName,String variablesJson) {}
}
