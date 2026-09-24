package com.yeremitech.mailplatform.api;

import com.yeremitech.mailplatform.api.controller.AttachmentRetentionAdminController;
import com.yeremitech.mailplatform.api.service.MailMaintenance;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"app.worker.enabled=false","spring.rabbitmq.listener.simple.auto-startup=false"})
@EnabledIfEnvironmentVariable(named="MAIL_PLATFORM_SMOKE_DB_URL",matches=".+")
class AttachmentRetentionIntegrationTest {
    @Autowired AttachmentRetentionAdminController controller;
    @Autowired NamedParameterJdbcTemplate jdbc;
    @Autowired MailMaintenance maintenance;

    @Test void heldOrRetainedAttachmentsSurviveOrphanCleanup() {
        UUID held=insertOldAttachment();
        UUID retained=insertOldAttachment();
        UUID orphan=insertOldAttachment();
        String client="retention-fixture";
        try {
            controller.update(held,new AttachmentRetentionAdminController.RetentionUpdate(client,true,null,"Legal case pending"));
            controller.update(retained,new AttachmentRetentionAdminController.RetentionUpdate(client,false,
                    Instant.now().plusSeconds(86400),"Contractual retention"));
            maintenance.cleanup();
            assertTrue(exists(held));
            assertTrue(exists(retained));
            assertFalse(exists(orphan));
            assertTrue(controller.retention(held,client).legalHold());
        } finally {
            jdbc.update("delete from mail_attachment where id in (:held,:retained,:orphan)",
                    Map.of("held",held,"retained",retained,"orphan",orphan));
        }
    }

    private UUID insertOldAttachment() {
        UUID id=UUID.randomUUID();
        jdbc.update("""
                insert into mail_attachment(id,client_id,filename,content_type,size_bytes,storage_key,
                    checksum_sha256,created_at,content)
                values (:id,'retention-fixture','test.txt','text/plain',1,:key,:hash,
                    current_timestamp - interval '72 hours',:content)
                """, new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                .addValue("id",id).addValue("key","db:"+id)
                .addValue("hash","0".repeat(64)).addValue("content",new byte[]{1}));
        return id;
    }
    private boolean exists(UUID id) {
        return jdbc.queryForObject("select count(*) from mail_attachment where id=:id", Map.of("id",id),Integer.class)==1;
    }
}
