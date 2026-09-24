package com.yeremitech.mailplatform.application.usecase;

import static org.junit.jupiter.api.Assertions.*;

import com.yeremitech.mailplatform.application.model.MailMessageData;
import com.yeremitech.mailplatform.application.port.MailMessageRepository;
import com.yeremitech.mailplatform.application.port.MailSubmissionPort;
import com.yeremitech.mailplatform.domain.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class QueueEmailUseCaseTest {
    @Test
    void sensitiveVariablesAreNotStoredInPublicMessageVariables() {
        CapturingSubmission submission = new CapturingSubmission();
        MailMessageRepository repository = new EmptyRepository();
        QueueEmailUseCase useCase = new QueueEmailUseCase(repository, submission, Clock.fixed(Instant.parse("2026-09-23T12:00:00Z"), ZoneOffset.UTC));

        useCase.execute(new QueueEmailUseCase.Command(
                "inventory", "recovery:1", "Código", "security/password-reset",
                Map.of("expiresMinutes", 10), Map.of("code", "123456"), Instant.parse("2026-09-23T12:10:00Z"),
                List.of(new MailRecipient(new EmailAddress("user@example.com"), RecipientType.TO, null)),
                List.of(), MailPriority.SECURITY, null));

        assertEquals(10, submission.message.variables().get("expiresMinutes"));
        assertFalse(submission.message.variables().containsKey("code"));
        assertEquals("123456", submission.sensitive.get("code"));
    }

    @Test
    void reusedIdempotencyKeyWithDifferentContentIsRejected() {
        Instant now = Instant.parse("2026-09-23T12:00:00Z");
        MailMessageData existing = new MailMessageData(UUID.randomUUID(), "inventory", "same-key", "Original",
                "billing/invoice", Map.of(), List.of(new MailRecipient(new EmailAddress("user@example.com"), RecipientType.TO, null)),
                List.of(), MailPriority.TRANSACTIONAL, MailStatus.QUEUED, null, now, now, null, null);
        MailMessageRepository repository = new EmptyRepository() {
            @Override public Optional<MailMessageData> findByClientIdAndIdempotencyKey(String clientId, String key) {
                return Optional.of(existing);
            }
        };
        QueueEmailUseCase useCase = new QueueEmailUseCase(repository, new CapturingSubmission(), Clock.fixed(now, ZoneOffset.UTC));
        assertThrows(IllegalStateException.class, () -> useCase.execute(new QueueEmailUseCase.Command(
                "inventory", "same-key", "Changed", "billing/invoice", Map.of(), Map.of(), null,
                existing.recipients(), List.of(), MailPriority.TRANSACTIONAL, null)));
    }

    private static final class CapturingSubmission implements MailSubmissionPort {
        MailMessageData message; Map<String,Object> sensitive;
        public MailMessageData submit(MailMessageData message, Map<String,Object> sensitive, Instant expiresAt) {
            this.message=message; this.sensitive=sensitive; return message;
        }
    }
    private static class EmptyRepository implements MailMessageRepository {
        public MailMessageData save(MailMessageData m){return m;}
        public Optional<MailMessageData> findById(UUID id){return Optional.empty();}
        public Optional<MailMessageData> findByClientIdAndIdempotencyKey(String c,String k){return Optional.empty();}
        public long countByBatchId(UUID id){return 0;}
        public long countByBatchIdAndStatus(UUID id,MailStatus s){return 0;}
    }
}
