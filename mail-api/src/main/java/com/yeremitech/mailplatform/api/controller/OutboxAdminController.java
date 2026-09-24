package com.yeremitech.mailplatform.api.controller;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** Read/requeue durable dead Outbox events; this is distinct from RabbitMQ's transport DLQs. */
@RestController
@RequestMapping("/api/v1/admin/outbox")
public class OutboxAdminController {
    private final NamedParameterJdbcTemplate jdbc;
    public OutboxAdminController(NamedParameterJdbcTemplate jdbc) { this.jdbc=jdbc; }

    @GetMapping("/dead")
    public List<DeadEvent> dead(@RequestParam(defaultValue="50") int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be 1..100");
        return jdbc.query("""
                select id, aggregate_id, event_type, attempts, created_at, last_error
                from mail_outbox_event where status='DEAD' and coalesce(last_error,'') <> 'Cancelled by client'
                order by created_at desc limit :limit
                """, Map.of("limit",limit), (rs,row)->new DeadEvent(
                rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getString(3),
                rs.getInt(4),rs.getTimestamp(5).toInstant(),rs.getString(6)));
    }

    /** Manual retry cannot claim exactly-once SMTP delivery; inspect delivery attempts before retrying. */
    @PostMapping("/{eventId}/retry")
    @Transactional
    public Object retry(@PathVariable UUID eventId) {
        List<UUID> messages=jdbc.query("""
                select aggregate_id from mail_outbox_event
                where id=:id and status='DEAD' and last_error <> 'Cancelled by client'
                for update
                """, Map.of("id",eventId),(rs,row)->rs.getObject(1,UUID.class));
        if (messages.isEmpty()) throw new NoSuchElementException("retryable dead event not found");
        UUID mail=messages.getFirst();
        List<String> statuses=jdbc.query("select status from mail_message where id=:id for update",
                Map.of("id",mail),(rs,row)->rs.getString(1));
        if (statuses.isEmpty()) throw new NoSuchElementException("mail message not found");
        String status=statuses.getFirst();
        if (!"FAILED".equals(status) && !"QUEUED".equals(status) && !"RETRYING".equals(status)) {
            throw new IllegalStateException("mail status does not allow manual retry");
        }
        Integer accepted=jdbc.queryForObject("""
                select count(*) from mail_delivery_attempt where message_id=:id and status='SUCCEEDED'
                """,Map.of("id",mail),Integer.class);
        if (accepted!=null && accepted>0) throw new IllegalStateException("SMTP already accepted this mail");
        jdbc.update("""
                update mail_message set status='QUEUED', last_error=null,updated_at=current_timestamp
                where id=:id
                """,Map.of("id",mail));
        jdbc.update("""
                update mail_outbox_event set status='PENDING',attempts=0,locked_until=null,
                    next_attempt_at=current_timestamp,last_error=null,updated_at=current_timestamp
                where id=:id
                """,Map.of("id",eventId));
        return Map.of("eventId",eventId,"messageId",mail,"status","PENDING");
    }

    public record DeadEvent(UUID eventId,UUID messageId,String eventType,int attempts,
                            java.time.Instant createdAt,String lastError){}
}
