package com.yeremitech.mailplatform.api.service;

import com.yeremitech.mailplatform.domain.EmailAddress;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Tenant-specific opt-outs. Transactional messages are not blocked by marketing suppression. */
@Service
public class SuppressionService {
    public enum Reason { UNSUBSCRIBED, COMPLAINT, BOUNCE, MANUAL }
    private final NamedParameterJdbcTemplate jdbc;
    public SuppressionService(NamedParameterJdbcTemplate jdbc) { this.jdbc=jdbc; }

    @Transactional
    public Suppression suppress(String clientId,String email,Reason reason) {
        if (reason==null) throw new IllegalArgumentException("suppression reason is required");
        String normalized=normalize(email);
        // A legacy-only client must first be provisioned persistently to use marketing features.
        int inserted=jdbc.update("""
                insert into mail_recipient_suppression(client_id,email,reason)
                values (:client,:email,:reason)
                on conflict (client_id,email) do update set reason=case
                    when mail_recipient_suppression.reason in ('COMPLAINT','BOUNCE')
                    then mail_recipient_suppression.reason else excluded.reason end,
                    updated_at=current_timestamp
                """,Map.of("client",clientId,"email",normalized,"reason",reason.name()));
        if(inserted!=1) throw new IllegalStateException("unable to persist suppression");
        String storedReason=jdbc.queryForObject("""
                select reason from mail_recipient_suppression where client_id=:client and email=:email
                """, Map.of("client",clientId,"email",normalized),String.class);
        return new Suppression(normalized,storedReason);
    }

    public Set<String> findSuppressed(String clientId,List<String> addresses) {
        if(addresses.isEmpty()) return Set.of();
        Set<String> found=new HashSet<>();
        // PostgreSQL JDBC parameter and SQL limits remain bounded even for 10k recipients.
        for(int offset=0;offset<addresses.size();offset+=500) {
            var window=addresses.subList(offset,Math.min(addresses.size(),offset+500));
            found.addAll(jdbc.query("""
                    select email from mail_recipient_suppression where client_id=:client and email in (:addresses)
                    """,new MapSqlParameterSource().addValue("client",clientId).addValue("addresses",window),
                    (rs,row)->rs.getString(1)));
        }
        return found;
    }

    public boolean isSuppressed(String clientId,String email) {
        return !findSuppressed(clientId,List.of(normalize(email))).isEmpty();
    }

    @Transactional
    public void remove(String clientId,String email) {
        int count=jdbc.update("delete from mail_recipient_suppression where client_id=:client and email=:email",
                Map.of("client",clientId,"email",normalize(email)));
        if(count==0) throw new NoSuchElementException("suppression not found");
    }

    public List<SuppressionDetail> recent(String clientId,int limit) {
        if(limit<1||limit>100) throw new IllegalArgumentException("limit must be between 1 and 100");
        return jdbc.query("""
                select email,reason,created_at,updated_at from mail_recipient_suppression
                where client_id=:client order by updated_at desc,email limit :limit
                """,Map.of("client",clientId,"limit",limit),
                (rs,row)->new SuppressionDetail(rs.getString(1),rs.getString(2),
                        rs.getTimestamp(3).toInstant(),rs.getTimestamp(4).toInstant()));
    }

    public static String normalize(String email) { return new EmailAddress(email).value(); }
    public record Suppression(String email,String reason) {}
    public record SuppressionDetail(String email,String reason,Instant createdAt,Instant updatedAt) {}
}
