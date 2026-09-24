package com.yeremitech.mailplatform.infrastructure.adapter;

import com.yeremitech.mailplatform.application.model.MailMessageData;
import com.yeremitech.mailplatform.application.port.MailMessageRepository;
import com.yeremitech.mailplatform.application.port.MailProcessingPort;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

public final class JdbcMailProcessingAdapter implements MailProcessingPort {
    private final NamedParameterJdbcTemplate jdbc;
    private final MailMessageRepository repository;

    public JdbcMailProcessingAdapter(NamedParameterJdbcTemplate jdbc, MailMessageRepository repository) {
        this.jdbc = jdbc;
        this.repository = repository;
    }

    @Override
    public Optional<MailMessageData> claim(UUID messageId, UUID processingToken, Instant now) {
        int updated = jdbc.update("""
                update mail_message
                set status='PROCESSING', processing_token=:processingToken,
                    processing_started_at=:now, updated_at=:now
                where id=:id and status in ('QUEUED','RETRYING')
                """, Map.of("id", messageId, "processingToken", processingToken, "now", JdbcTime.value(now)));
        return updated == 1 ? repository.findById(messageId) : Optional.empty();
    }

    @Override
    public boolean isCurrentClaim(UUID messageId, UUID processingToken) {
        Boolean current = jdbc.queryForObject("""
                select exists (
                    select 1 from mail_message
                    where id=:id and status='PROCESSING' and processing_token=:token
                )
                """, Map.of("id", messageId, "token", processingToken), Boolean.class);
        return Boolean.TRUE.equals(current);
    }

    @Override
    public boolean heartbeat(UUID messageId, UUID processingToken, Instant now) {
        return jdbc.update("""
                update mail_message set updated_at=:now
                where id=:id and status='PROCESSING' and processing_token=:token
                """, Map.of("id", messageId, "token", processingToken, "now", JdbcTime.value(now))) == 1;
    }

    @Override
    public boolean markSent(UUID messageId, UUID processingToken, String providerMessageId, Instant now) {
        return jdbc.update("""
                update mail_message
                set status='SENT', provider_message_id=:providerId, last_error=null,
                    processing_token=null, processing_started_at=null, updated_at=:now
                where id=:id and status='PROCESSING' and processing_token=:token
                """, new MapSqlParameterSource()
                .addValue("id", messageId)
                .addValue("token", processingToken)
                .addValue("providerId", providerMessageId)
                .addValue("now", JdbcTime.value(now))) == 1;
    }

    @Override
    public boolean markFailed(UUID messageId, UUID processingToken, String error, Instant now) {
        String safeError = error == null ? null : error.substring(0, Math.min(error.length(), 4000));
        return jdbc.update("""
                update mail_message
                set status='FAILED', last_error=:error,
                    processing_token=null, processing_started_at=null, updated_at=:now
                where id=:id and status='PROCESSING' and processing_token=:token
                """, new MapSqlParameterSource()
                .addValue("id", messageId)
                .addValue("token", processingToken)
                .addValue("error", safeError)
                .addValue("now", JdbcTime.value(now))) == 1;
    }
}
