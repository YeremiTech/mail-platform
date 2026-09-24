package com.yeremitech.mailplatform.api.controller;

import com.yeremitech.mailplatform.api.service.AdminAuditService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** Admin-only retention controls. Never logs attachment bytes or legal-case free-text reasons. */
@RestController
@RequestMapping("/api/v1/admin/attachments")
public final class AttachmentRetentionAdminController {
    private final NamedParameterJdbcTemplate jdbc;
    private final AdminAuditService audit;

    public AttachmentRetentionAdminController(NamedParameterJdbcTemplate jdbc, AdminAuditService audit) {
        this.jdbc = jdbc; this.audit = audit;
    }

    @GetMapping("/{id}/retention")
    public RetentionView retention(@PathVariable UUID id, @RequestParam String clientId) {
        return jdbc.query("""
                select client_id,legal_hold,retention_until from mail_attachment
                where id=:id and client_id=:client
                """, Map.of("id",id,"client",clientId), (rs,row) -> new RetentionView(
                id,rs.getString(1),rs.getBoolean(2),
                rs.getTimestamp(3)==null ? null : rs.getTimestamp(3).toInstant()))
                .stream().findFirst().orElseThrow(() -> new NoSuchElementException("attachment not found"));
    }

    @PutMapping("/{id}/retention")
    @Transactional
    public RetentionView update(@PathVariable UUID id, @Valid @RequestBody RetentionUpdate request) {
        if (request.reason().trim().isEmpty()) throw new IllegalArgumentException("audit reason is required");
        if (request.retentionUntil()!=null && request.retentionUntil().isBefore(Instant.now())) {
            throw new IllegalArgumentException("retentionUntil must be in the future");
        }
        int updated=jdbc.update("""
                update mail_attachment
                set legal_hold=:hold,retention_until=:until
                where id=:id and client_id=:client
                """, new MapSqlParameterSource().addValue("id",id)
                .addValue("client",request.clientId()).addValue("hold",request.legalHold())
                .addValue("until",request.retentionUntil()==null ? null : java.sql.Timestamp.from(request.retentionUntil())));
        if (updated!=1) throw new NoSuchElementException("attachment not found");
        // Log only change categories, not the potentially sensitive legal reason text.
        audit.record(request.legalHold() ? "ATTACHMENT_HOLD_APPLIED" : "ATTACHMENT_RETENTION_UPDATED",
                request.clientId(),id,"legalHold,retentionUntil,reasonProvided");
        return retention(id,request.clientId());
    }

    public record RetentionUpdate(@NotBlank String clientId, @NotNull Boolean legalHold,
            Instant retentionUntil, @NotBlank @Size(min=8,max=500) String reason) {}
    public record RetentionView(UUID id,String clientId,boolean legalHold,Instant retentionUntil) {}
}
