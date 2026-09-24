package com.yeremitech.mailplatform.infrastructure.adapter;

import com.yeremitech.mailplatform.application.port.MailRetryPort;
import com.yeremitech.mailplatform.domain.MailPriority;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

public class JdbcMailRetryAdapter implements MailRetryPort {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcMailRetryAdapter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public boolean scheduleRetry(
            UUID messageId,
            UUID processingToken,
            MailPriority priority,
            Instant nextAttemptAt,
            String lastError,
            Instant now) {
        var update = new MapSqlParameterSource()
                .addValue("id", messageId)
                .addValue("token", processingToken)
                .addValue("error", truncate(lastError, 4000))
                .addValue("now", JdbcTime.value(now));
        int changed = jdbc.update("""
                update mail_message
                set status='RETRYING', last_error=:error, updated_at=:now,
                    processing_token=null, processing_started_at=null
                where id=:id and status='PROCESSING' and processing_token=:token
                """, update);
        if (changed == 0) {
            return false;
        }

        jdbc.update("""
                insert into mail_outbox_event (
                    id, aggregate_id, event_type, routing_key, payload,
                    status, attempts, next_attempt_at, created_at, updated_at
                ) values (
                    :id, :aggregateId, 'MAIL_RETRY_SCHEDULED', :routingKey, :payload,
                    'PENDING', 0, :nextAttemptAt, :createdAt, :updatedAt
                )
                """, Map.of(
                "id", UUID.randomUUID(),
                "aggregateId", messageId,
                "routingKey", "mail." + priority.name().toLowerCase(),
                "payload", messageId.toString(),
                "nextAttemptAt", JdbcTime.value(nextAttemptAt),
                "createdAt", JdbcTime.value(now),
                "updatedAt", JdbcTime.value(now)));
        return true;
    }

    @Override
    @Transactional
    public int recoverStaleProcessing(Instant olderThan, Instant now, int limit) {
        var params = new MapSqlParameterSource()
                .addValue("olderThan", JdbcTime.value(olderThan))
                .addValue("now", JdbcTime.value(now))
                .addValue("limit", limit);
        var rows = jdbc.query(
                """
                with picked as (
                    select id
                    from mail_message
                    where status='PROCESSING' and updated_at < :olderThan
                    order by updated_at
                    for update skip locked
                    limit :limit
                )
                update mail_message m
                set status='RETRYING',
                    last_error='Recovered stale PROCESSING message after worker interruption',
                    processing_token=null, processing_started_at=null, updated_at=:now
                from picked
                where m.id=picked.id
                returning m.id, m.priority
                """,
                params,
                (rs, rowNum) -> new Object[] {
                        rs.getObject("id", UUID.class),
                        rs.getString("priority")
                });

        for (Object[] row : rows) {
            UUID messageId = (UUID) row[0];
            String priority = (String) row[1];
            jdbc.update(
                    """
                    insert into mail_outbox_event (
                        id, aggregate_id, event_type, routing_key, payload,
                        status, attempts, next_attempt_at, created_at, updated_at
                    ) values (
                        :id, :aggregateId, 'MAIL_STALE_RECOVERED', :routingKey, :payload,
                        'PENDING', 0, :nextAttemptAt, :createdAt, :updatedAt
                    )
                    """,
                    Map.of(
                            "id", UUID.randomUUID(),
                            "aggregateId", messageId,
                            "routingKey", "mail." + priority.toLowerCase(),
                            "payload", messageId.toString(),
                            "nextAttemptAt", JdbcTime.value(now),
                            "createdAt", JdbcTime.value(now),
                            "updatedAt", JdbcTime.value(now)));
        }
        return rows.size();
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}
