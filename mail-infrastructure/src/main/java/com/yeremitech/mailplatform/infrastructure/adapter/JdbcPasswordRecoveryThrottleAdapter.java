package com.yeremitech.mailplatform.infrastructure.adapter;

import com.yeremitech.mailplatform.application.port.PasswordRecoveryThrottlePort;
import com.yeremitech.mailplatform.application.error.RateLimitExceededException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

public class JdbcPasswordRecoveryThrottleAdapter implements PasswordRecoveryThrottlePort {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcPasswordRecoveryThrottleAdapter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void acquire(
            String clientId,
            String subjectReference,
            Instant now,
            Duration window,
            int maxRequests,
            Duration cooldown) {
        Map<String, Object> key = Map.of(
                "clientId", clientId,
                "subjectReference", subjectReference,
                "now", JdbcTime.value(now));

        jdbc.update("""
                insert into password_recovery_throttle(
                    client_id, subject_reference, window_started_at, request_count, last_request_at
                ) values (:clientId, :subjectReference, :now, 0, null)
                on conflict (client_id, subject_reference) do nothing
                """, key);

        var rows = jdbc.query("""
                select window_started_at, request_count, last_request_at
                from password_recovery_throttle
                where client_id=:clientId and subject_reference=:subjectReference
                for update
                """, key, (rs, rowNum) -> new State(
                rs.getTimestamp("window_started_at").toInstant(),
                rs.getInt("request_count"),
                rs.getTimestamp("last_request_at") == null ? null : rs.getTimestamp("last_request_at").toInstant()));

        State state = rows.getFirst();
        if (state.lastRequestAt() != null && state.lastRequestAt().plus(cooldown).isAfter(now)) {
            throw new RateLimitExceededException("recovery request cooldown is active");
        }

        Instant windowStart = state.windowStartedAt();
        int count = state.requestCount();
        if (!windowStart.plus(window).isAfter(now)) {
            windowStart = now;
            count = 0;
        }
        if (count >= maxRequests) {
            throw new RateLimitExceededException("recovery request rate limit exceeded");
        }

        jdbc.update("""
                update password_recovery_throttle
                set window_started_at=:windowStart,
                    request_count=:requestCount,
                    last_request_at=:now
                where client_id=:clientId and subject_reference=:subjectReference
                """, Map.of(
                "clientId", clientId,
                "subjectReference", subjectReference,
                "windowStart", JdbcTime.value(windowStart),
                "requestCount", count + 1,
                "now", JdbcTime.value(now)));
    }

    private record State(Instant windowStartedAt, int requestCount, Instant lastRequestAt) {}
}
