package com.yeremitech.mailplatform.api.controller;

import static org.junit.jupiter.api.Assertions.*;

import com.yeremitech.mailplatform.application.model.MailMessageData;
import com.yeremitech.mailplatform.application.port.AttachmentStoragePort;
import com.yeremitech.mailplatform.application.port.MailMessageRepository;
import com.yeremitech.mailplatform.application.port.MailSubmissionPort;
import com.yeremitech.mailplatform.application.usecase.QueueEmailUseCase;
import com.yeremitech.mailplatform.domain.AttachmentRef;
import com.yeremitech.mailplatform.domain.MailPriority;
import com.yeremitech.mailplatform.domain.MailStatus;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DocumentCopyControllerTest {
    @Test
    void queuesFacturaCopyWithPdfForAuthenticatedClient() {
        UUID attachmentId = UUID.randomUUID();
        CapturingSubmission submission = new CapturingSubmission();
        DocumentCopyController controller = controller(attachmentId, "%PDF-1.7".getBytes(), submission);
        var request = new DocumentCopyController.CopyRequest(DocumentCopyController.DocumentType.FACTURA,
                "F001-123", "Cliente", "Emisor", new BigDecimal("25.50"), "PEN",
                List.of(new DocumentCopyController.RecipientDto("client@example.com", "Cliente")), List.of(attachmentId));

        var response = controller.send(() -> "inventory", "resend-F001-123-1", request);

        assertEquals(202, response.getStatusCode().value());
        assertEquals("inventory", submission.message.clientId());
        assertEquals("billing/document-copy", submission.message.templateKey());
        assertEquals(MailPriority.DOCUMENT, submission.message.priority());
        assertEquals("F001-123", submission.message.variables().get("documentNumber"));
    }

    @Test
    void rejectsSpoofedPdfBeforeQueuing() {
        UUID attachmentId = UUID.randomUUID();
        CapturingSubmission submission = new CapturingSubmission();
        DocumentCopyController controller = controller(attachmentId, "wrong".getBytes(), submission);
        var request = new DocumentCopyController.CopyRequest(DocumentCopyController.DocumentType.BOLETA,
                "B001-123", "Cliente", "Emisor", new BigDecimal("10.00"), "PEN",
                List.of(new DocumentCopyController.RecipientDto("client@example.com", null)), List.of(attachmentId));

        assertThrows(IllegalArgumentException.class, () -> controller.send(() -> "inventory", "resend-B001-123-1", request));
        assertNull(submission.message);
    }

    private DocumentCopyController controller(UUID attachmentId, byte[] content, CapturingSubmission submission) {
        AttachmentStoragePort attachments = new AttachmentStoragePort() {
            public AttachmentRef store(String c, String f, String t, InputStream i, long s) { throw new UnsupportedOperationException(); }
            public Optional<StoredAttachment> load(String clientId, UUID id) {
                if (!"inventory".equals(clientId) || !attachmentId.equals(id)) return Optional.empty();
                AttachmentRef ref = new AttachmentRef(id, clientId, "copy.pdf", "application/pdf", content.length,
                        "db:" + id, "checksum");
                return Optional.of(new StoredAttachment(ref, new ByteArrayInputStream(content)));
            }
            public boolean belongsTo(String c, UUID id) { return "inventory".equals(c) && attachmentId.equals(id); }
        };
        MailMessageRepository repository = new MailMessageRepository() {
            public MailMessageData save(MailMessageData m) { return m; }
            public Optional<MailMessageData> findById(UUID id) { return Optional.empty(); }
            public Optional<MailMessageData> findByClientIdAndIdempotencyKey(String c, String k) { return Optional.empty(); }
            public long countByBatchId(UUID id) { return 0; }
            public long countByBatchIdAndStatus(UUID id, MailStatus status) { return 0; }
        };
        QueueEmailUseCase queue = new QueueEmailUseCase(repository, submission,
                Clock.fixed(Instant.parse("2026-09-23T12:00:00Z"), ZoneOffset.UTC));
        return new DocumentCopyController(queue, attachments);
    }

    private static class CapturingSubmission implements MailSubmissionPort {
        MailMessageData message;
        public MailMessageData submit(MailMessageData message, Map<String, Object> sensitive, Instant expiresAt) {
            this.message = message;
            return message;
        }
    }
}
