package com.yeremitech.mailplatform.infrastructure.adapter;

import com.yeremitech.mailplatform.application.model.DeliveryAttemptData;
import com.yeremitech.mailplatform.application.port.DeliveryAttemptRepository;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

public final class JdbcDeliveryAttemptAdapter implements DeliveryAttemptRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcDeliveryAttemptAdapter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int nextAttemptNumber(UUID messageId) {
        Integer value = jdbc.queryForObject(
                "select coalesce(max(attempt_number), 0) + 1 from mail_delivery_attempt where message_id=:messageId",
                Map.of("messageId", messageId),
                Integer.class);
        return value == null ? 1 : value;
    }

    @Override
    public DeliveryAttemptData save(DeliveryAttemptData attempt) {
        var params = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                .addValue("id", attempt.id())
                .addValue("messageId", attempt.messageId())
                .addValue("attemptNumber", attempt.attemptNumber())
                .addValue("providerKey", attempt.providerKey())
                .addValue("status", attempt.status().name())
                .addValue("startedAt", JdbcTime.value(attempt.startedAt()))
                .addValue("finishedAt", JdbcTime.value(attempt.finishedAt()))
                .addValue("providerMessageId", attempt.providerMessageId())
                .addValue("errorType", attempt.errorType())
                .addValue("errorMessage", truncate(attempt.errorMessage(), 4000));
        jdbc.update("""
                insert into mail_delivery_attempt (
                    id, message_id, attempt_number, provider_key, status,
                    started_at, finished_at, provider_message_id, error_type, error_message
                ) values (
                    :id, :messageId, :attemptNumber, :providerKey, :status,
                    :startedAt, :finishedAt, :providerMessageId, :errorType, :errorMessage
                )
                on conflict (id) do update set
                    status=excluded.status,
                    finished_at=excluded.finished_at,
                    provider_message_id=excluded.provider_message_id,
                    error_type=excluded.error_type,
                    error_message=excluded.error_message
                """, params);
        return attempt;
    }

    @Override
    public java.util.List<DeliveryAttemptData> findByMessageId(UUID messageId) {
        return jdbc.query(
                """
                select id, message_id, attempt_number, provider_key, status, started_at, finished_at,
                       provider_message_id, error_type, error_message
                from mail_delivery_attempt
                where message_id=:messageId
                order by attempt_number
                """,
                Map.of("messageId", messageId),
                (rs, rowNum) -> new DeliveryAttemptData(
                        rs.getObject("id", UUID.class),
                        rs.getObject("message_id", UUID.class),
                        rs.getInt("attempt_number"),
                        rs.getString("provider_key"),
                        com.yeremitech.mailplatform.domain.DeliveryAttemptStatus.valueOf(rs.getString("status")),
                        rs.getTimestamp("started_at").toInstant(),
                        rs.getTimestamp("finished_at") == null ? null : rs.getTimestamp("finished_at").toInstant(),
                        rs.getString("provider_message_id"),
                        rs.getString("error_type"),
                        rs.getString("error_message")));
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}
