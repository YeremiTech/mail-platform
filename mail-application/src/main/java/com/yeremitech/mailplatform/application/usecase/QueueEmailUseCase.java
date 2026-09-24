package com.yeremitech.mailplatform.application.usecase;

import com.yeremitech.mailplatform.application.model.MailMessageData;
import com.yeremitech.mailplatform.application.port.MailMessageRepository;
import com.yeremitech.mailplatform.application.port.MailSubmissionPort;
import com.yeremitech.mailplatform.domain.MailPriority;
import com.yeremitech.mailplatform.domain.MailRecipient;
import com.yeremitech.mailplatform.domain.MailStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public final class QueueEmailUseCase {
    private static final Pattern CLIENT_ID = Pattern.compile("^[a-zA-Z0-9._-]{1,120}$");
    private static final Pattern TEMPLATE_KEY = Pattern.compile("^[a-z0-9][a-z0-9/_-]{0,199}$");

    private final MailMessageRepository repository;
    private final MailSubmissionPort submission;
    private final Clock clock;

    public QueueEmailUseCase(
            MailMessageRepository repository,
            MailSubmissionPort submission,
            Clock clock) {
        this.repository = repository;
        this.submission = submission;
        this.clock = clock;
    }

    public MailMessageData execute(Command command) {
        validate(command);
        if (command.idempotencyKey() != null && !command.idempotencyKey().isBlank()) {
            var existing = repository.findByClientIdAndIdempotencyKey(command.clientId(), command.idempotencyKey());
            if (existing.isPresent()) {
                ensureSameRequest(existing.get(), command);
                return existing.get();
            }
        }

        Instant now = clock.instant();
        MailMessageData message = new MailMessageData(
                UUID.randomUUID(), command.clientId(), blankToNull(command.idempotencyKey()),
                command.subject(), command.templateKey(),
                command.variables() == null ? Map.of() : Map.copyOf(command.variables()),
                List.copyOf(command.recipients()),
                command.attachmentIds() == null ? List.of() : List.copyOf(command.attachmentIds()),
                command.priority() == null ? MailPriority.TRANSACTIONAL : command.priority(),
                MailStatus.QUEUED, command.batchId(), now, now, null, null, command.scheduledAt());

        Map<String, Object> sensitive = command.sensitiveVariables() == null ? Map.of() : Map.copyOf(command.sensitiveVariables());
        Instant sensitiveExpiresAt = sensitive.isEmpty() ? null : (command.sensitiveExpiresAt() == null ? now.plus(Duration.ofMinutes(15)) : command.sensitiveExpiresAt());
        return submission.submit(message, sensitive, sensitiveExpiresAt);
    }

    private void validate(Command command) {
        if (command.clientId() == null || !CLIENT_ID.matcher(command.clientId()).matches()) throw new IllegalArgumentException("invalid clientId");
        if (command.subject() == null || command.subject().isBlank() || command.subject().length() > 300) throw new IllegalArgumentException("subject is required and must not exceed 300 characters");
        if (containsLineBreak(command.subject())) throw new IllegalArgumentException("subject contains invalid line breaks");
        if (command.templateKey() == null || !TEMPLATE_KEY.matcher(command.templateKey()).matches()) throw new IllegalArgumentException("invalid templateKey");
        if (command.recipients() == null || command.recipients().isEmpty()) throw new IllegalArgumentException("at least one recipient is required");
        if (command.recipients().size() > 100) throw new IllegalArgumentException("a single message cannot exceed 100 recipients");
        if (command.scheduledAt() != null && (command.scheduledAt().isBefore(clock.instant())
                || command.scheduledAt().isAfter(clock.instant().plus(Duration.ofDays(30))))) {
            throw new IllegalArgumentException("scheduledAt must be between now and 30 days from now");
        }
        if (command.idempotencyKey() != null && command.idempotencyKey().length() > 200) throw new IllegalArgumentException("idempotencyKey exceeds 200 characters");
        if (command.attachmentIds() != null && command.attachmentIds().size() > 10) throw new IllegalArgumentException("a single message cannot exceed 10 attachments");
    }

    private static boolean containsLineBreak(String value) { return value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0; }
    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value; }

    private static void ensureSameRequest(MailMessageData existing, Command command) {
        if (!Objects.equals(existing.subject(), command.subject())
                || !Objects.equals(existing.templateKey(), command.templateKey())
                || !Objects.equals(existing.variables(), command.variables() == null ? Map.of() : command.variables())
                || !Objects.equals(existing.recipients(), command.recipients())
                || !Objects.equals(existing.attachmentIds(), command.attachmentIds() == null ? List.of() : command.attachmentIds())
                || existing.priority() != (command.priority() == null ? MailPriority.TRANSACTIONAL : command.priority())
                || !Objects.equals(existing.batchId(), command.batchId())
                || !Objects.equals(existing.scheduledAt(), command.scheduledAt())) {
            throw new IllegalStateException("idempotency key already belongs to a different request");
        }
    }

    public record Command(
            String clientId,
            String idempotencyKey,
            String subject,
            String templateKey,
            Map<String, Object> variables,
            Map<String, Object> sensitiveVariables,
            Instant sensitiveExpiresAt,
            List<MailRecipient> recipients,
            List<UUID> attachmentIds,
            MailPriority priority,
            UUID batchId,
            Instant scheduledAt) {
        public Command(String clientId, String idempotencyKey, String subject, String templateKey,
                Map<String,Object> variables, Map<String,Object> sensitiveVariables, Instant sensitiveExpiresAt,
                List<MailRecipient> recipients, List<UUID> attachmentIds, MailPriority priority, UUID batchId) {
            this(clientId, idempotencyKey, subject, templateKey, variables, sensitiveVariables, sensitiveExpiresAt,
                    recipients, attachmentIds, priority, batchId, null);
        }
    }
}
