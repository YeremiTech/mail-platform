package com.yeremitech.mailplatform.api.service;

import com.yeremitech.mailplatform.api.security.PersistentClientCredentials;
import com.yeremitech.mailplatform.api.security.ClientTemplatePolicy;
import com.yeremitech.mailplatform.application.usecase.CreateBatchUseCase;
import com.yeremitech.mailplatform.infrastructure.adapter.JdbcMarketingDeliveryPolicyAdapter;
import com.yeremitech.mailplatform.application.model.MailMessageData;
import com.yeremitech.mailplatform.domain.*;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"app.worker.enabled=false","spring.rabbitmq.listener.simple.auto-startup=false","app.marketing.public-base-url=http://127.0.0.1:8080"})
@EnabledIfEnvironmentVariable(named="MAIL_PLATFORM_SMOKE_DB_URL",matches=".+")
class SuppressionPolicyIntegrationTest {
    @Autowired SuppressionService suppressions;
    @Autowired CampaignService campaigns;
    @Autowired PersistentClientCredentials credentials;
    @Autowired NamedParameterJdbcTemplate jdbc;
    @Autowired DynamicTemplateService templates;
    @Autowired ClientTemplatePolicy policy;

    @Test void marketingRequiresConsentAndSkipsSuppressedWithoutBlockingTransactionalMail() {
        Assumptions.assumeTrue(credentials.configured());
        String client="suppression-"+UUID.randomUUID();
        credentials.createClient(client,"Suppression tests",120,Duration.ofDays(1),java.util.Set.of("EMAIL_SEND","EMAIL_READ","EMAIL_CANCEL","TEMPLATE_READ","TEMPLATE_WRITE","CAMPAIGN_READ","CAMPAIGN_WRITE","SUPPRESSION_READ","SUPPRESSION_WRITE","WEBHOOK_READ","WEBHOOK_WRITE"));
        templates.create(client,"notice",new DynamicTemplateService.Body("<p>Hello</p>","Hello",List.of()));
        templates.publish(client,"notice",1);
        String template=policy.resolveForQueue(client,"custom/notice");
        suppressions.suppress(client,"A@EXAMPLE.TEST",SuppressionService.Reason.UNSUBSCRIBED);
        assertTrue(suppressions.isSuppressed(client,"a@example.test"));
        assertFalse(suppressions.isSuppressed("another-tenant","a@example.test"));
        List<CreateBatchUseCase.Recipient> recipients=List.of(
                new CreateBatchUseCase.Recipient("A@EXAMPLE.TEST","A",Map.of()),
                new CreateBatchUseCase.Recipient("b@example.test","B",Map.of()));
        assertThrows(IllegalArgumentException.class,()->campaigns.stage(client,"Notice",template,
                Map.of(),List.of(),recipients,"MARKETING",Set.of("a@example.test")));
        var marketing=campaigns.stage(client,"Notice",template,Map.of(),List.of(),recipients,
                "MARKETING",Set.of("a@example.test","b@example.test"));
        assertEquals(1,marketing.suppressedRecipients());
        assertEquals(1,marketing.stagedRecipients());
        assertEquals(1,campaigns.suppressedCount(client,marketing.batchId()));
        assertEquals(1,campaigns.stagedRemaining(client,marketing.batchId()));
        var transactional=campaigns.stage(client,"Transactional",template,Map.of(),List.of(),recipients);
        assertEquals(0,transactional.suppressedRecipients());
        assertEquals(2,transactional.stagedRecipients());
        suppressions.suppress(client,"b@example.test",SuppressionService.Reason.COMPLAINT);
        assertFalse(new JdbcMarketingDeliveryPolicyAdapter(jdbc).mayDeliver(mockMarketingMail(client,marketing.batchId(),"b@example.test")));
        assertTrue(new JdbcMarketingDeliveryPolicyAdapter(jdbc).mayDeliver(mockMarketingMail(client,transactional.batchId(),"a@example.test")));
        var allExcluded=campaigns.stage(client,"No eligible recipients",template,Map.of(),List.of(),recipients,
                "MARKETING",Set.of("a@example.test","b@example.test"));
        assertEquals(0,allExcluded.stagedRecipients());
        assertEquals(2,allExcluded.suppressedRecipients());
        suppressions.remove(client,"a@example.test");
        assertFalse(suppressions.isSuppressed(client,"a@example.test"));
    }

    private static MailMessageData mockMarketingMail(String client,UUID batch,String email) {
        Instant now=Instant.now();
        return new MailMessageData(UUID.randomUUID(),client,null,"Notice","custom/notice/v1",Map.of(),
                List.of(new MailRecipient(new EmailAddress(email),RecipientType.TO,null)),List.of(),
                MailPriority.BULK,MailStatus.QUEUED,batch,now,now,null,null);
    }
}
