package com.yeremitech.mailplatform.api;

import com.yeremitech.mailplatform.api.security.ClientTemplatePolicy;
import com.yeremitech.mailplatform.api.security.PersistentClientCredentials;
import com.yeremitech.mailplatform.api.service.CampaignService;
import com.yeremitech.mailplatform.api.service.DynamicTemplateService;
import com.yeremitech.mailplatform.api.service.MailCancellationService;
import com.yeremitech.mailplatform.api.service.SignedWebhookService;
import com.yeremitech.mailplatform.application.port.MailMessageRepository;
import com.yeremitech.mailplatform.application.port.MailProcessingPort;
import com.yeremitech.mailplatform.application.usecase.CreateBatchUseCase;
import com.yeremitech.mailplatform.application.usecase.QueueEmailUseCase;
import com.yeremitech.mailplatform.domain.EmailAddress;
import com.yeremitech.mailplatform.domain.MailPriority;
import com.yeremitech.mailplatform.domain.MailRecipient;
import com.yeremitech.mailplatform.domain.MailStatus;
import com.yeremitech.mailplatform.domain.RecipientType;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties={"app.worker.enabled=false","spring.rabbitmq.listener.simple.auto-startup=false","app.marketing.public-base-url=http://127.0.0.1:8080"})
@EnabledIfEnvironmentVariable(named="MAIL_PLATFORM_SMOKE_DB_URL",matches=".+")
@AutoConfigureMockMvc
class MailUpgradeIntegrationTest {
    @Autowired PersistentClientCredentials credentials;
    @Autowired DynamicTemplateService templates;
    @Autowired ClientTemplatePolicy policy;
    @Autowired QueueEmailUseCase queue;
    @Autowired MailMessageRepository messages;
    @Autowired MailCancellationService cancel;
    @Autowired CampaignService campaigns;
    @Autowired MailProcessingPort processing;
    @Autowired SignedWebhookService webhooks;
    @Autowired NamedParameterJdbcTemplate jdbc;
    @Autowired MockMvc mvc;

    @Test
    void clientProvisioningQuotaRotationAndAdminIsolation() throws Exception {
        Assumptions.assumeTrue(credentials.configured(),"configure API_KEY_PEPPER for the upgrade integration suite");
        String client="upgrade-"+UUID.randomUUID();
        var issued=credentials.createClient(client,"Upgrade test",2,Duration.ofDays(1),java.util.Set.of("EMAIL_SEND","EMAIL_READ","EMAIL_CANCEL","TEMPLATE_READ","TEMPLATE_WRITE","CAMPAIGN_READ","CAMPAIGN_WRITE","SUPPRESSION_READ","SUPPRESSION_WRITE","WEBHOOK_READ","WEBHOOK_WRITE"));
        assertTrue(credentials.authenticate(client,issued.apiKey()));
        assertTrue(credentials.consumeRequestQuota(client));
        assertTrue(credentials.consumeRequestQuota(client));
        assertFalse(credentials.consumeRequestQuota(client));
        var replacement=credentials.issueKey(client,Duration.ofDays(1));
        assertTrue(credentials.authenticate(client,replacement.apiKey()));
        credentials.revoke(client,issued.credentialId());
        assertFalse(credentials.authenticate(client,issued.apiKey()));
        assertTrue(credentials.authenticate(client,replacement.apiKey()));
        credentials.setEnabled(client,false);
        assertFalse(credentials.authenticate(client,replacement.apiKey()));
        String admin=System.getenv("ADMIN_API_KEY");
        if(admin!=null&&!admin.isBlank()) {
            mvc.perform(get("/api/v1/admin/clients")
                    .header("X-Client-Id","inventory")
                    .header("X-Internal-Api-Key",System.getenv("E2E_API_KEY")))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/api/v1/admin/clients")
                    .header("X-Client-Id","admin")
                    .header("X-Internal-Api-Key",admin))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void pinTemplateVersionAndCancelScheduledEmail() {
        Assumptions.assumeTrue(credentials.configured());
        String client="upgrade-"+UUID.randomUUID();
        credentials.createClient(client,"Template test",120,Duration.ofDays(1),java.util.Set.of("EMAIL_SEND","EMAIL_READ","EMAIL_CANCEL","TEMPLATE_READ","TEMPLATE_WRITE","CAMPAIGN_READ","CAMPAIGN_WRITE","SUPPRESSION_READ","SUPPRESSION_WRITE","WEBHOOK_READ","WEBHOOK_WRITE"));
        templates.create(client,"welcome",new DynamicTemplateService.Body(
                "<p>Hi {{name}}</p>","Hi {{name}}",List.of("name")));
        templates.publish(client,"welcome",1);
        String pinned=policy.resolveForQueue(client,"custom/welcome");
        assertEquals("custom/welcome/v1",pinned);
        assertEquals("<p>Hi &lt;em&gt;</p>",templates.render(client,pinned,Map.of("name","<em>")).html());
        templates.revise(client,"welcome",new DynamicTemplateService.Body(
                "<p>Hello {{name}}</p>","Hello {{name}}",List.of("name")));
        templates.publish(client,"welcome",2);
        assertEquals(pinned,"custom/welcome/v1");
        assertEquals("custom/welcome/v2",policy.resolveForQueue(client,"custom/welcome"));
        assertThrows(RuntimeException.class,()->policy.resolveForQueue("other-tenant","custom/welcome"));
        Instant scheduled=Instant.now().plus(Duration.ofHours(3));
        var command=new QueueEmailUseCase.Command(client,"scheduled-"+UUID.randomUUID(),"Welcome",pinned,
                Map.of("name","Ana"),Map.of(),null,
                List.of(new MailRecipient(new EmailAddress("ana@example.test"),RecipientType.TO,null)),
                List.of(),MailPriority.TRANSACTIONAL,null,scheduled);
        var message=queue.execute(command);
        assertEquals(MailStatus.QUEUED,messages.findById(message.id()).orElseThrow().status());
        var eventDates=jdbc.query("select next_attempt_at from mail_outbox_event where aggregate_id=:id",
                Map.of("id",message.id()),(rs,row)->rs.getTimestamp(1).toInstant());
        assertEquals(scheduled.getEpochSecond(),eventDates.getFirst().getEpochSecond());
        cancel.cancelOne(client,message.id());
        assertEquals(MailStatus.CANCELLED,messages.findById(message.id()).orElseThrow().status());
    }

    @Test
    void stagedCampaignCanBeDrainedCancelledAndWebhookEventRecorded() {
        Assumptions.assumeTrue(credentials.configured());
        String client="upgrade-"+UUID.randomUUID();
        credentials.createClient(client,"Campaign test",500,Duration.ofDays(1),java.util.Set.of("EMAIL_SEND","EMAIL_READ","EMAIL_CANCEL","TEMPLATE_READ","TEMPLATE_WRITE","CAMPAIGN_READ","CAMPAIGN_WRITE","SUPPRESSION_READ","SUPPRESSION_WRITE","WEBHOOK_READ","WEBHOOK_WRITE"));
        templates.create(client,"notice",new DynamicTemplateService.Body(
                "<p>{{name}}</p>","{{name}}",List.of("name")));
        templates.publish(client,"notice",1);
        List<CreateBatchUseCase.Recipient> recipients=new ArrayList<>();
        for(int i=0;i<102;i++) recipients.add(new CreateBatchUseCase.Recipient(
                "person"+i+"@example.test",null,Map.of("name","Person "+i)));
        assertThrows(IllegalArgumentException.class, () -> campaigns.stage(client, "Invalid", "custom/notice/v1",
                Map.of(), List.of(), List.of(recipients.getFirst(), recipients.getFirst())));
        var staged=campaigns.stage(client,"Campaign","custom/notice/v1",Map.of(),List.of(),recipients);
        assertEquals(102,campaigns.stagedRemaining(client,staged.batchId()));
        assertEquals(100,campaigns.drainOneChunk());
        assertEquals(2,campaigns.stagedRemaining(client,staged.batchId()));
        assertEquals(2,campaigns.drainOneChunk());
        assertEquals(0,campaigns.stagedRemaining(client,staged.batchId()));
        var second=campaigns.stage(client,"Cancel","custom/notice/v1",Map.of(),List.of(),recipients.subList(0,2));
        cancel.cancelBatch(client,second.batchId());
        assertEquals(0,campaigns.stagedRemaining(client,second.batchId()));
        // No external HTTP needed: test PostgreSQL's terminal-state webhook trigger.
        jdbc.update("""
                insert into mail_webhook_subscription(client_id,endpoint,nonce,encrypted_secret)
                values (:client,'https://example.org/hook',:nonce,:cipher)
                """,new MapSqlParameterSource().addValue("client",client)
                .addValue("nonce",new byte[12]).addValue("cipher",new byte[32]));
        var mail=queue.execute(new QueueEmailUseCase.Command(client,"trigger-"+UUID.randomUUID(),"Trigger",
                "custom/notice/v1",Map.of("name","Ana"),Map.of(),null,
                List.of(new MailRecipient(new EmailAddress("ana@example.test"),RecipientType.TO,null)),
                List.of(),MailPriority.TRANSACTIONAL,null));
        Instant now=Instant.now();
        UUID token=UUID.randomUUID();
        assertTrue(processing.claim(mail.id(),token,now).isPresent());
        assertTrue(processing.markSent(mail.id(),token,"smtp-test",now));
        assertTrue(webhooks.deliveries(client,20).stream().anyMatch(d->
                d.messageId().equals(mail.id())&&d.eventType().equals("mail.sent")&&d.status().equals("PENDING")));
    }
}
