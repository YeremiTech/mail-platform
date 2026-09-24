package com.yeremitech.mailplatform.infrastructure.adapter;

import com.yeremitech.mailplatform.application.model.MailMessageData;
import com.yeremitech.mailplatform.application.port.DeliveryPolicyPort;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Fail closed if a marketing recipient has opted out before SMTP delivery. */
public final class JdbcMarketingDeliveryPolicyAdapter implements DeliveryPolicyPort {
    private final NamedParameterJdbcTemplate jdbc;
    public JdbcMarketingDeliveryPolicyAdapter(NamedParameterJdbcTemplate jdbc) { this.jdbc=jdbc; }

    @Override public boolean isMarketing(MailMessageData message) {
        if (message.batchId()==null) return false;
        Boolean marketing = jdbc.queryForObject("""
                select exists(select 1 from mail_batch
                where id=:batch and client_id=:client and purpose='MARKETING')
                """, Map.of("batch",message.batchId(),"client",message.clientId()),Boolean.class);
        return Boolean.TRUE.equals(marketing);
    }

    @Override public boolean mayDeliver(MailMessageData message) {
        if (message.batchId()==null) return true;
        var purpose = jdbc.query("select purpose from mail_batch where id=:batch and client_id=:client",
                Map.of("batch",message.batchId(),"client",message.clientId()),(rs,row)->rs.getString(1));
        if (purpose.isEmpty()) return false; // A missing campaign must never bypass marketing checks.
        if (!"MARKETING".equals(purpose.getFirst())) return true;
        for (var recipient : message.recipients()) {
            Boolean suppressed = jdbc.queryForObject("""
                    select exists(select 1 from mail_recipient_suppression
                    where client_id=:client and email=:email)
                    """,Map.of("client",message.clientId(),"email",recipient.email().value()),Boolean.class);
            if (Boolean.TRUE.equals(suppressed)) return false;
        }
        return true;
    }
}
