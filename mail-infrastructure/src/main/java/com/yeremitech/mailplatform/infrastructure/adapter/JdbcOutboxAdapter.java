package com.yeremitech.mailplatform.infrastructure.adapter;

import com.yeremitech.mailplatform.application.model.OutboxEventData;
import com.yeremitech.mailplatform.application.port.OutboxRepository;
import com.yeremitech.mailplatform.domain.OutboxStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

public class JdbcOutboxAdapter implements OutboxRepository {
    private static final String CLAIM = """
            with picked as (
                select id
                from mail_outbox_event
                where (
                    (status in ('PENDING','FAILED') and next_attempt_at <= :now)
                    or (status = 'PUBLISHING' and locked_until <= :now)
                )
                order by case routing_key when 'mail.security' then 0 when 'mail.transactional' then 1 when 'mail.document' then 2 when 'mail.bulk' then 3 else 9 end, created_at
                for update skip locked
                limit :limit
            )
            update mail_outbox_event o
            set status = 'PUBLISHING', locked_until = :lockedUntil, updated_at = :now
            from picked
            where o.id = picked.id
            returning o.*
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcOutboxAdapter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<OutboxEventData> claimReady(Instant now, Instant lockedUntil, int limit) {
        var params = new MapSqlParameterSource()
                .addValue("now", JdbcTime.value(now))
                .addValue("lockedUntil", JdbcTime.value(lockedUntil))
                .addValue("limit", limit);
        return jdbc.query(CLAIM, params, this::map);
    }

    @Override
    public boolean markPublished(UUID eventId, Instant lockedUntil, Instant publishedAt) {
        return jdbc.update("""
                update mail_outbox_event
                set status='PUBLISHED', published_at=:publishedAt, locked_until=null,
                    last_error=null, updated_at=:publishedAt
                where id=:id and status='PUBLISHING' and locked_until=:lockedUntil
                """, Map.of("id", eventId, "lockedUntil", JdbcTime.value(lockedUntil),
                "publishedAt", JdbcTime.value(publishedAt))) == 1;
    }

    @Override
    @Transactional
    public boolean markFailed(
            UUID eventId,
            Instant lockedUntil,
            int attempts,
            Instant nextAttemptAt,
            boolean terminal,
            String error,
            Instant now) {
        var params = new MapSqlParameterSource()
                .addValue("id", eventId)
                .addValue("lockedUntil", JdbcTime.value(lockedUntil))
                .addValue("attempts", attempts)
                .addValue("status", terminal ? "DEAD" : "FAILED")
                .addValue("nextAttemptAt", JdbcTime.value(nextAttemptAt))
                .addValue("error", truncate(error, 4000))
                .addValue("now", JdbcTime.value(now));
        int changed = jdbc.update("""
                update mail_outbox_event
                set status=:status, attempts=:attempts, next_attempt_at=:nextAttemptAt,
                    locked_until=null, last_error=:error, updated_at=:now
                where id=:id and status='PUBLISHING' and locked_until=:lockedUntil
                """, params);
        if (changed == 1 && terminal) {
            jdbc.update("""
                    update mail_message
                    set status='FAILED', last_error=:error, updated_at=:now
                    where id=(select aggregate_id from mail_outbox_event where id=:id)
                      and status in ('QUEUED','RETRYING')
                    """, params);
        }
        return changed == 1;
    }

    private OutboxEventData map(ResultSet rs, int rowNum) throws SQLException {
        return new OutboxEventData(
                rs.getObject("id", UUID.class),
                rs.getObject("aggregate_id", UUID.class),
                rs.getString("event_type"),
                rs.getString("routing_key"),
                rs.getString("payload"),
                OutboxStatus.valueOf(rs.getString("status")),
                rs.getInt("attempts"),
                instant(rs, "next_attempt_at"),
                instant(rs, "locked_until"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"),
                instant(rs, "published_at"),
                rs.getString("last_error"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}
