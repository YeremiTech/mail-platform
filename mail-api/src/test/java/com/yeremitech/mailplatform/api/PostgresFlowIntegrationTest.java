package com.yeremitech.mailplatform.api;

import static org.junit.jupiter.api.Assertions.*;

import com.yeremitech.mailplatform.api.service.PasswordRecoveryRequestService;
import com.yeremitech.mailplatform.application.port.AttachmentStoragePort;
import com.yeremitech.mailplatform.application.port.MailMessageRepository;
import com.yeremitech.mailplatform.application.port.MailProcessingPort;
import com.yeremitech.mailplatform.application.port.MailRetryPort;
import com.yeremitech.mailplatform.application.port.OutboxRepository;
import com.yeremitech.mailplatform.application.port.DeliveryAttemptRepository;
import com.yeremitech.mailplatform.application.model.DeliveryAttemptData;
import com.yeremitech.mailplatform.application.port.SensitivePayloadPort;
import com.yeremitech.mailplatform.application.usecase.ConsumeResetGrantUseCase;
import com.yeremitech.mailplatform.application.usecase.QueueEmailUseCase;
import com.yeremitech.mailplatform.application.usecase.RequestPasswordRecoveryUseCase;
import com.yeremitech.mailplatform.application.usecase.VerifyPasswordRecoveryUseCase;
import com.yeremitech.mailplatform.domain.EmailAddress;
import com.yeremitech.mailplatform.domain.MailPriority;
import com.yeremitech.mailplatform.domain.MailRecipient;
import com.yeremitech.mailplatform.domain.MailStatus;
import com.yeremitech.mailplatform.domain.RecipientType;
import com.yeremitech.mailplatform.domain.DeliveryAttemptStatus;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Run with MAIL_PLATFORM_SMOKE_DB_URL, DB_URL and the required secret environment variables. */
@SpringBootTest(properties = {
        "app.worker.enabled=false",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "app.outbox.poll-interval-ms=600000",
        "app.delivery.stale-recovery-interval-ms=600000",
        "app.sensitive-payload.cleanup-interval-ms=600000"
})
@EnabledIfEnvironmentVariable(named = "MAIL_PLATFORM_SMOKE_DB_URL", matches = ".+")
@AutoConfigureMockMvc
class PostgresFlowIntegrationTest {
    @Autowired AttachmentStoragePort attachments;
    @Autowired MailMessageRepository messages;
    @Autowired SensitivePayloadPort sensitive;
    @Autowired QueueEmailUseCase queue;
    @Autowired PasswordRecoveryRequestService recoveryRequests;
    @Autowired VerifyPasswordRecoveryUseCase verify;
    @Autowired ConsumeResetGrantUseCase consume;
    @Autowired OutboxRepository outbox;
    @Autowired MailProcessingPort processing;
    @Autowired MailRetryPort retry;
    @Autowired DeliveryAttemptRepository attempts;
    @Autowired MockMvc mvc;

    @Test
    void persistsDocumentAndCompletesRecoveryGrant() throws Exception {
        String unique = UUID.randomUUID().toString();
        byte[] pdf = "%PDF-1.7\nsmoke test".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        var attachment = attachments.store("inventory", "copy.pdf", "application/pdf",
                new ByteArrayInputStream(pdf), pdf.length);
        try (var input = attachments.load("inventory", attachment.id()).orElseThrow().content()) {
            assertArrayEquals(pdf, input.readAllBytes());
        }
        assertTrue(attachments.load("another-client", attachment.id()).isEmpty());

        var document = queue.execute(new QueueEmailUseCase.Command("inventory", "document:" + unique,
                "Duplicado F001-123", "billing/document-copy",
                Map.of("customerName", "Cliente", "documentNumber", "F001-123", "amount", "25.50",
                        "currency", "PEN", "documentType", "factura", "issuerName", "Emisor"),
                Map.of(), null,
                List.of(new MailRecipient(new EmailAddress("client@example.com"), RecipientType.TO, null)),
                List.of(attachment.id()), MailPriority.DOCUMENT, null));
        assertEquals(MailStatus.QUEUED, messages.findById(document.id()).orElseThrow().status());
        Instant now = Instant.now();
        var claimedEvents = outbox.claimReady(now, now.plusSeconds(120), 1000);
        var documentEvent = claimedEvents.stream().filter(e -> e.aggregateId().equals(document.id()))
                .findFirst().orElseThrow();
        assertFalse(outbox.markPublished(documentEvent.id(), now.plusSeconds(999), now));
        assertTrue(outbox.markPublished(documentEvent.id(), documentEvent.lockedUntil(), now));
        UUID processingToken = UUID.randomUUID();
        assertTrue(processing.claim(document.id(), processingToken, now).isPresent());
        attempts.save(new DeliveryAttemptData(UUID.randomUUID(), document.id(), 1, "smtp",
                DeliveryAttemptStatus.STARTED, now, null, null, null, null));
        assertEquals(1, attempts.findByMessageId(document.id()).size());
        assertTrue(retry.scheduleRetry(document.id(), processingToken, MailPriority.DOCUMENT,
                now.plusSeconds(30), "temporary failure", now));
        assertEquals(MailStatus.RETRYING, messages.findById(document.id()).orElseThrow().status());
        assertFalse(processing.markSent(document.id(), processingToken, "stale-provider-id", now));

        var challenge = recoveryRequests.request(new RequestPasswordRecoveryUseCase.Command(
                "inventory", "user-" + unique, "client@example.com"));
        var otpMail = messages.findByClientIdAndIdempotencyKey("inventory", "password-recovery:" + challenge.challengeId())
                .orElseThrow();
        assertFalse(otpMail.variables().containsKey("code"));
        String code = (String) sensitive.load(otpMail.id()).get("code");
        assertNotNull(code);
        assertThrows(IllegalArgumentException.class,
                () -> verify.execute("another-client", challenge.challengeId(), code));
        var grant = verify.execute("inventory", challenge.challengeId(), code);
        var used = consume.execute("inventory", grant.grantId(), grant.resetGrant());
        assertEquals("user-" + unique, used.subjectReference());
        assertThrows(IllegalArgumentException.class,
                () -> consume.execute("inventory", grant.grantId(), grant.resetGrant()));
    }

    @Test
    void staleWorkerCannotOverwriteNewerDelivery() {
        String unique = UUID.randomUUID().toString();
        var mail = queue.execute(new QueueEmailUseCase.Command("inventory", "stale-claim:" + unique,
                "Test", "billing/document-copy",
                Map.of("customerName", "Cliente", "documentNumber", "F001-123", "amount", "25.50",
                        "currency", "PEN", "documentType", "factura", "issuerName", "Emisor"),
                Map.of(), null,
                List.of(new MailRecipient(new EmailAddress("client@example.com"), RecipientType.TO, null)),
                List.of(), MailPriority.DOCUMENT, null));
        Instant now = Instant.now();
        UUID firstToken = UUID.randomUUID();
        assertTrue(processing.claim(mail.id(), firstToken, now.minusSeconds(600)).isPresent());
        assertTrue(processing.heartbeat(mail.id(), firstToken, now));
        assertEquals(0, retry.recoverStaleProcessing(now.minusSeconds(300), now, 100));
        assertEquals(1, retry.recoverStaleProcessing(now.plusSeconds(1), now, 100));
        assertFalse(processing.isCurrentClaim(mail.id(), firstToken));
        assertFalse(processing.markSent(mail.id(), firstToken, "old-provider-id", now));
        assertFalse(processing.markFailed(mail.id(), firstToken, "old failure", now));
        assertFalse(retry.scheduleRetry(mail.id(), firstToken, MailPriority.DOCUMENT,
                now.plusSeconds(30), "old retry", now));
        UUID secondToken = UUID.randomUUID();
        assertTrue(processing.claim(mail.id(), secondToken, now).isPresent());
        assertTrue(processing.markSent(mail.id(), secondToken, "new-provider-id", now));
        assertEquals(MailStatus.SENT, messages.findById(mail.id()).orElseThrow().status());
        assertFalse(processing.markFailed(mail.id(), firstToken, "late failure", now));
        assertEquals("new-provider-id", messages.findById(mail.id()).orElseThrow().providerMessageId());
    }

    @Test
    void outboxFailureNeedsCurrentLeaseAndMarksMailFailed() {
        String unique = UUID.randomUUID().toString();
        var mail = queue.execute(new QueueEmailUseCase.Command("inventory", "outbox-terminal:" + unique,
                "Test", "billing/invoice", Map.of("customerName", "Cliente"), Map.of(), null,
                List.of(new MailRecipient(new EmailAddress("client@example.com"), RecipientType.TO, null)),
                List.of(), MailPriority.TRANSACTIONAL, null));
        Instant now = Instant.now();
        var event = outbox.claimReady(now, now.plusSeconds(120), 1000).stream()
                .filter(e -> e.aggregateId().equals(mail.id())).findFirst().orElseThrow();
        assertFalse(outbox.markFailed(event.id(), now.plusSeconds(999), 1,
                now.plusSeconds(60), true, "stale publisher", now));
        assertEquals(MailStatus.QUEUED, messages.findById(mail.id()).orElseThrow().status());
        assertTrue(outbox.markFailed(event.id(), event.lockedUntil(), 1,
                now.plusSeconds(60), true, "broker unavailable", now));
        assertEquals(MailStatus.FAILED, messages.findById(mail.id()).orElseThrow().status());
    }

    @Test
    void httpRejectsUnauthenticatedCallsAndConflictingDocumentCopies() throws Exception {
        byte[] pdf = "%PDF-1.7\nhttp test".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        var unauthenticated = mvc.perform(multipart("/api/v1/attachments")
                .file(new MockMultipartFile("file", "copy.pdf", "application/pdf", pdf))).andReturn();
        assertEquals(401, unauthenticated.getResponse().getStatus());

        var upload = mvc.perform(multipart("/api/v1/attachments")
                .file(new MockMultipartFile("file", "copy.pdf", "application/pdf", pdf))
                .header("X-Client-Id", "inventory")
                .header("X-Internal-Api-Key", "development-only-client-secret-32-bytes-minimum")).andReturn();
        assertEquals(201, upload.getResponse().getStatus());
        String attachmentId = upload.getResponse().getContentAsString().replaceAll("(?s).*\"id\":\"([^\"]+)\".*", "$1");
        UUID.fromString(attachmentId);

        String key = "http-copy-" + UUID.randomUUID();
        String payload = """
                {"documentType":"FACTURA","documentNumber":"F001-123","customerName":"Cliente",
                 "issuerName":"Emisor","amount":25.50,"currency":"PEN",
                 "recipients":[{"email":"client@example.com"}],"attachmentIds":["%s"]}
                """.formatted(attachmentId);
        var first = mvc.perform(post("/api/v1/documents/copies")
                .header("X-Client-Id", "inventory")
                .header("X-Internal-Api-Key", "development-only-client-secret-32-bytes-minimum")
                .header("Idempotency-Key", key)
                .contentType("application/json").content(payload)).andReturn();
        assertEquals(202, first.getResponse().getStatus(), first.getResponse().getContentAsString());

        var conflict = mvc.perform(post("/api/v1/documents/copies")
                .header("X-Client-Id", "inventory")
                .header("X-Internal-Api-Key", "development-only-client-secret-32-bytes-minimum")
                .header("Idempotency-Key", key)
                .contentType("application/json").content(payload.replace("F001-123", "F001-124"))).andReturn();
        assertEquals(409, conflict.getResponse().getStatus(), conflict.getResponse().getContentAsString());
    }
}
