package com.yeremitech.mailplatform.api.service;

import com.yeremitech.mailplatform.api.security.PersistentClientCredentials;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Run against disposable PostgreSQL with Flyway V1..V12 and the configured test services. */
@SpringBootTest(properties={"app.worker.enabled=false","spring.rabbitmq.listener.simple.auto-startup=false",
        "app.marketing.public-base-url=http://127.0.0.1:8080"})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named="MAIL_PLATFORM_SMOKE_DB_URL",matches=".+")
class PublicUnsubscribeIntegrationTest {
    @Autowired UnsubscribeLinkService links;
    @Autowired SuppressionService suppressions;
    @Autowired PersistentClientCredentials credentials;
    @Autowired NamedParameterJdbcTemplate jdbc;
    @Autowired MockMvc mvc;

    @Test void publicGetIsReadOnlyAndPostConsumesTokenExactlyOnce() throws Exception {
        Assumptions.assumeTrue(credentials.configured());
        String first="unsubscribe-"+UUID.randomUUID();
        String other="unsubscribe-"+UUID.randomUUID();
        credentials.createClient(first,"First tenant",100,Duration.ofDays(1),java.util.Set.of("EMAIL_SEND","EMAIL_READ","EMAIL_CANCEL","TEMPLATE_READ","TEMPLATE_WRITE","CAMPAIGN_READ","CAMPAIGN_WRITE","SUPPRESSION_READ","SUPPRESSION_WRITE","WEBHOOK_READ","WEBHOOK_WRITE"));
        credentials.createClient(other,"Other tenant",100,Duration.ofDays(1),java.util.Set.of("EMAIL_SEND","EMAIL_READ","EMAIL_CANCEL","TEMPLATE_READ","TEMPLATE_WRITE","CAMPAIGN_READ","CAMPAIGN_WRITE","SUPPRESSION_READ","SUPPRESSION_WRITE","WEBHOOK_READ","WEBHOOK_WRITE"));
        String url=links.issue(first,"A@EXAMPLE.TEST");
        String token=URI.create(url).getRawQuery().substring("token=".length());
        assertFalse(suppressions.isSuppressed(first,"a@example.test"));
        mvc.perform(get("/api/v1/public/unsubscribe").param("token",token))
                .andExpect(status().isOk());
        assertFalse(suppressions.isSuppressed(first,"a@example.test"),"GET must not change state");
        assertFalse(suppressions.isSuppressed(other,"a@example.test"));
        mvc.perform(post("/api/v1/public/unsubscribe").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("token",token)).andExpect(status().isOk());
        assertTrue(suppressions.isSuppressed(first,"a@example.test"));
        assertFalse(suppressions.isSuppressed(other,"a@example.test"));
        mvc.perform(post("/api/v1/public/unsubscribe").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("token",token)).andExpect(status().isOk());
        Long consumed=jdbc.queryForObject("select count(*) from mail_unsubscribe_token where consumed_at is not null and client_id=:client",
                Map.of("client",first),Long.class);
        assertEquals(1L,consumed);
        mvc.perform(post("/api/v1/public/unsubscribe").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("token",(token.startsWith("A") ? "B" : "A") + token.substring(1))).andExpect(status().isOk());
        assertFalse(suppressions.isSuppressed(other,"a@example.test"));
    }

    @Test void expiredLinksDoNotSuppressAndComplaintsAreNeverDowngraded() throws Exception {
        Assumptions.assumeTrue(credentials.configured());
        String client="unsubscribe-"+UUID.randomUUID();
        credentials.createClient(client,"Expiration tenant",100,Duration.ofDays(1),java.util.Set.of("EMAIL_SEND","EMAIL_READ","EMAIL_CANCEL","TEMPLATE_READ","TEMPLATE_WRITE","CAMPAIGN_READ","CAMPAIGN_WRITE","SUPPRESSION_READ","SUPPRESSION_WRITE","WEBHOOK_READ","WEBHOOK_WRITE"));
        String token=URI.create(links.issue(client,"expired@example.test")).getRawQuery().substring(6);
        String digest=com.yeremitech.mailplatform.domain.UnsubscribeToken.digest(token);
        jdbc.update("""
                update mail_unsubscribe_token
                set issued_at=current_timestamp - interval '2 days',
                    expires_at=current_timestamp - interval '1 day'
                where token_digest=:digest
                """,Map.of("digest",digest));
        mvc.perform(post("/api/v1/public/unsubscribe").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("token",token)).andExpect(status().isOk());
        assertFalse(suppressions.isSuppressed(client,"expired@example.test"));
        suppressions.suppress(client,"complaint@example.test",SuppressionService.Reason.COMPLAINT);
        String valid=URI.create(links.issue(client,"complaint@example.test")).getRawQuery().substring(6);
        mvc.perform(post("/api/v1/public/unsubscribe").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("token",valid)).andExpect(status().isOk());
        String reason=jdbc.queryForObject("""
                select reason from mail_recipient_suppression where client_id=:client and email=:email
                """,Map.of("client",client,"email","complaint@example.test"),String.class);
        assertEquals("COMPLAINT",reason);
    }
    @Test void oneClickRequiresPostMarkerAndNeverConsumesOnGet() throws Exception {
        Assumptions.assumeTrue(credentials.configured());
        String client = "oneclick-" + UUID.randomUUID();
        credentials.createClient(client, "One-click test", 100, Duration.ofDays(1),
                java.util.Set.of("EMAIL_SEND", "CAMPAIGN_WRITE"));
        String token = URI.create(links.issue(client, "oneclick@example.test")).getRawQuery().substring(6);
        mvc.perform(get("/api/v1/public/unsubscribe/one-click").param("token", token))
                .andExpect(status().isOk());
        assertFalse(suppressions.isSuppressed(client, "oneclick@example.test"));
        mvc.perform(post("/api/v1/public/unsubscribe/one-click")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).param("token", token))
                .andExpect(status().isBadRequest());
        assertFalse(suppressions.isSuppressed(client, "oneclick@example.test"));
        mvc.perform(post("/api/v1/public/unsubscribe/one-click")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).param("token", token)
                .param("List-Unsubscribe", "One-Click"))
                .andExpect(status().isOk());
        assertTrue(suppressions.isSuppressed(client, "oneclick@example.test"));
    }

}
