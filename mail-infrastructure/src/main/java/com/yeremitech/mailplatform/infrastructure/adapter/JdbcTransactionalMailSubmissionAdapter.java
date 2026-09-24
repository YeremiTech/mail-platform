package com.yeremitech.mailplatform.infrastructure.adapter;

import com.yeremitech.mailplatform.application.model.MailMessageData;
import com.yeremitech.mailplatform.application.error.RateLimitExceededException;
import com.yeremitech.mailplatform.application.port.MailMessageRepository;
import com.yeremitech.mailplatform.application.port.MailSubmissionPort;
import com.yeremitech.mailplatform.application.port.SensitivePayloadPort;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

public class JdbcTransactionalMailSubmissionAdapter implements MailSubmissionPort {
    private static final String INSERT_MAIL = """
            insert into mail_message (
                id, client_id, idempotency_key, subject, template_key,
                variables_json, recipients_json, attachment_ids_json,
                priority, status, batch_id, created_at, updated_at,
                last_error, provider_message_id, scheduled_at
            ) values (
                :id, :clientId, :idempotencyKey, :subject, :templateKey,
                :variablesJson, :recipientsJson, :attachmentIdsJson,
                :priority, :status, :batchId, :createdAt, :updatedAt,
                :lastError, :providerMessageId, :scheduledAt
            )
            on conflict (client_id, idempotency_key)
            where idempotency_key is not null
            do nothing
            """;

    private static final String INSERT_OUTBOX = """
            insert into mail_outbox_event (
                id, aggregate_id, event_type, routing_key, payload,
                status, attempts, next_attempt_at, created_at, updated_at
            ) values (
                :id, :aggregateId, 'MAIL_QUEUED', :routingKey, :payload,
                'PENDING', 0, :nextAttemptAt, :createdAt, :updatedAt
            )
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final JsonMapper json;
    private final MailMessageRepository readRepository;
    private final SensitivePayloadPort sensitivePayloads;

    public JdbcTransactionalMailSubmissionAdapter(
            NamedParameterJdbcTemplate jdbc,
            JsonMapper json,
            MailMessageRepository readRepository,
            SensitivePayloadPort sensitivePayloads) {
        this.jdbc = jdbc;
        this.json = json;
        this.readRepository = readRepository;
        this.sensitivePayloads = sensitivePayloads;
    }

    @Override
    @Transactional
    public MailMessageData submit(MailMessageData message, Map<String, Object> sensitiveVariables, Instant sensitiveExpiresAt) {
        if (message.idempotencyKey() != null) {
            var existing = readRepository.findByClientIdAndIdempotencyKey(
                    message.clientId(), message.idempotencyKey());
            if (existing.isPresent()) {
                ensureSameRequest(existing.get(), message);
                return existing.get();
            }
        }

        try {
            Map<String, Object> params = new HashMap<>();
            params.put("id", message.id());
            params.put("clientId", message.clientId());
            params.put("idempotencyKey", message.idempotencyKey());
            params.put("subject", message.subject());
            params.put("templateKey", message.templateKey());
            params.put("variablesJson", json.writeValueAsString(message.variables()));
            params.put("recipientsJson", json.writeValueAsString(message.recipients()));
            params.put("attachmentIdsJson", json.writeValueAsString(message.attachmentIds()));
            params.put("priority", message.priority().name());
            params.put("status", message.status().name());
            params.put("batchId", message.batchId());
            params.put("createdAt", JdbcTime.value(message.createdAt()));
            params.put("updatedAt", JdbcTime.value(message.updatedAt()));
            params.put("lastError", message.lastError());
            params.put("providerMessageId", message.providerMessageId());
            params.put("scheduledAt", JdbcTime.value(message.scheduledAt()));

            int inserted = jdbc.update(INSERT_MAIL, params);
            if (inserted == 0) {
                MailMessageData existing = readRepository.findByClientIdAndIdempotencyKey(
                                message.clientId(), message.idempotencyKey())
                        .orElseThrow(() -> new IllegalStateException("idempotent mail exists but cannot be loaded"));
                ensureSameRequest(existing, message);
                return existing;
            }

            reserveDailyQuota(message);

            Map<String, Object> outbox = Map.of(
                    "id", UUID.randomUUID(),
                    "aggregateId", message.id(),
                    "routingKey", routingKey(message),
                    "payload", message.id().toString(),
                    "nextAttemptAt", JdbcTime.value(message.scheduledAt() == null ? message.createdAt() : message.scheduledAt()),
                    "createdAt", JdbcTime.value(message.createdAt()),
                    "updatedAt", JdbcTime.value(message.createdAt()));
            jdbc.update(INSERT_OUTBOX, outbox);
            for (UUID attachmentId : message.attachmentIds()) {
                if (jdbc.update("""
                        insert into mail_message_attachment(message_id, attachment_id)
                        select :message, id from mail_attachment
                        where id=:attachment and client_id=:client
                        """, Map.of("message", message.id(), "attachment", attachmentId,
                        "client", message.clientId())) != 1) {
                    throw new IllegalArgumentException("attachment does not belong to client");
                }
            }
            if (sensitiveVariables != null && !sensitiveVariables.isEmpty()) {
                sensitivePayloads.store(message.id(), sensitiveVariables, sensitiveExpiresAt);
            }
            return message;
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("unable to persist mail and outbox event", ex);
        }
    }

    /** Reserve a slot in the same transaction as mail+Outbox creation. Legacy env-only clients bypass this until migrated. */
    private void reserveDailyQuota(MailMessageData mail) {
        record Limits(boolean enabled, int standard, int bulk) {}
        var configured = jdbc.query("""
                select enabled, daily_transactional_limit, daily_bulk_limit from mail_api_client
                where client_id=:client
                """, Map.of("client", mail.clientId()),
                (rs,row) -> new Limits(rs.getBoolean(1), rs.getInt(2), rs.getInt(3)));
        if (configured.isEmpty()) return;
        Limits limits=configured.getFirst();
        if (!limits.enabled()) throw new RateLimitExceededException("mail client is disabled");
        boolean bulk=mail.priority()==com.yeremitech.mailplatform.domain.MailPriority.BULK;
        int limit=bulk?limits.bulk():limits.standard();
        String channel=bulk?"BULK":"STANDARD";
        var used=jdbc.query("""
                insert into mail_api_daily_usage(client_id,usage_day,channel,accepted_count)
                values (:client,current_date,:channel,1)
                on conflict (client_id,usage_day,channel)
                do update set accepted_count=mail_api_daily_usage.accepted_count+1
                where mail_api_daily_usage.accepted_count < :limit
                returning accepted_count
                """, Map.of("client",mail.clientId(),"channel",channel,"limit",limit),
                (rs,row)->rs.getInt(1));
        if (used.isEmpty()) throw new RateLimitExceededException("client daily mail quota has been reached");
    }

    private static String routingKey(MailMessageData message) {
        return "mail." + message.priority().name().toLowerCase();
    }

    private static void ensureSameRequest(MailMessageData existing, MailMessageData requested) {
        if (!Objects.equals(existing.subject(), requested.subject())
                || !Objects.equals(existing.templateKey(), requested.templateKey())
                || !Objects.equals(existing.variables(), requested.variables())
                || !Objects.equals(existing.recipients(), requested.recipients())
                || !Objects.equals(existing.attachmentIds(), requested.attachmentIds())
                || existing.priority() != requested.priority()
                || !Objects.equals(existing.batchId(), requested.batchId())
                || !Objects.equals(existing.scheduledAt(), requested.scheduledAt())) {
            throw new IllegalStateException("idempotency key already belongs to a different request");
        }
    }
}
