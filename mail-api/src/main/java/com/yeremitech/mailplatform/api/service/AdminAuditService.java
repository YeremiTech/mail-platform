package com.yeremitech.mailplatform.api.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminAuditService {
    private final NamedParameterJdbcTemplate jdbc;
    public AdminAuditService(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Called in the same transaction as the administrative mutation. No secrets or payloads are recorded. */
    @Transactional
    public void record(String action, String clientId, UUID credentialId, String changedFields) {
        jdbc.update("""
                insert into mail_admin_audit(actor,action,target_client_id,reference_id,fields_changed)
                values ('admin',:action,:client,:reference,:fields)
                """, new MapSqlParameterSource().addValue("action", action)
                .addValue("client", clientId).addValue("reference", credentialId)
                .addValue("fields", changedFields));
    }

    public List<AuditEvent> recent(int limit) {
        if (limit < 1 || limit > 200) throw new IllegalArgumentException("limit must be 1..200");
        return jdbc.query("""
                select id,created_at,actor,action,target_client_id,reference_id,fields_changed
                from mail_admin_audit order by created_at desc,id desc limit :limit
                """, Map.of("limit",limit), (rs, n) -> new AuditEvent(rs.getLong(1),
                rs.getTimestamp(2).toInstant(), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getObject(6, UUID.class), rs.getString(7)));
    }

    public record AuditEvent(long id, Instant createdAt, String actor, String action,
            String clientId, UUID referenceId, String changedFields) {}
}
