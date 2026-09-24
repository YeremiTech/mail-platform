package com.yeremitech.mailplatform.api.service;

import com.yeremitech.mailplatform.application.port.SensitivePayloadPort;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Cancellation is accepted only before a worker claims the message. An already accepted SMTP message cannot be recalled. */
@Service
public class MailCancellationService {
    private final NamedParameterJdbcTemplate jdbc;
    private final SensitivePayloadPort sensitive;
    public MailCancellationService(NamedParameterJdbcTemplate jdbc, SensitivePayloadPort sensitive) {
        this.jdbc=jdbc; this.sensitive=sensitive;
    }

    @Transactional
    public Map<String,Object> cancelOne(String clientId, UUID id) {
        List<UUID> cancelled = jdbc.query("""
                update mail_message set status='CANCELLED', updated_at=current_timestamp
                where client_id=:client and id=:id and status in ('QUEUED','RETRYING')
                returning id
                """, Map.of("client", clientId, "id", id), (rs, row) -> rs.getObject(1,UUID.class));
        if (cancelled.isEmpty()) {
            List<String> statuses = jdbc.query("select status from mail_message where client_id=:client and id=:id",
                    Map.of("client", clientId, "id", id), (rs,row)->rs.getString(1));
            if (statuses.isEmpty()) throw new NoSuchElementException("mail message not found");
            if (!"CANCELLED".equals(statuses.getFirst())) throw new IllegalStateException("message was already claimed or delivered");
        } else {
            killOutbox(id);
            sensitive.purge(id);
        }
        return Map.of("id", id, "status", "CANCELLED");
    }

    @Transactional
    public Map<String,Object> cancelBatch(String clientId, UUID batchId) {
        List<UUID> exists = jdbc.query("select id from mail_batch where id=:id and client_id=:client for update",
                Map.of("id", batchId, "client", clientId), (rs, row) -> rs.getObject(1,UUID.class));
        if (exists.isEmpty()) throw new NoSuchElementException("batch not found");
        jdbc.update("update mail_campaign_recipient set state='CANCELLED' where batch_id=:id and state='PENDING'",
                Map.of("id", batchId));
        List<UUID> cancelled = jdbc.query("""
                update mail_message set status='CANCELLED', updated_at=current_timestamp
                where client_id=:client and batch_id=:id and status in ('QUEUED','RETRYING')
                returning id
                """, Map.of("client", clientId, "id", batchId), (rs, row) -> rs.getObject(1,UUID.class));
        for (UUID id : cancelled) {
            killOutbox(id);
            sensitive.purge(id);
        }
        jdbc.update("update mail_batch set status='CANCELLED', updated_at=current_timestamp where client_id=:client and id=:id",
                Map.of("client", clientId, "id", batchId));
        return Map.of("batchId", batchId, "cancelledPendingMessages", cancelled.size());
    }

    private void killOutbox(UUID id) {
        jdbc.update("""
                update mail_outbox_event set status='DEAD', updated_at=current_timestamp,
                    last_error='Cancelled by client'
                where aggregate_id=:id and status in ('PENDING','FAILED')
                """, Map.of("id", id));
    }
}
