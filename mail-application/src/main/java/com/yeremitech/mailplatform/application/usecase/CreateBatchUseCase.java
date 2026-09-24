package com.yeremitech.mailplatform.application.usecase;

import com.yeremitech.mailplatform.application.model.MailBatchData;
import com.yeremitech.mailplatform.application.port.MailBatchRepository;
import com.yeremitech.mailplatform.domain.BatchStatus;
import com.yeremitech.mailplatform.domain.EmailAddress;
import com.yeremitech.mailplatform.domain.MailPriority;
import com.yeremitech.mailplatform.domain.MailRecipient;
import com.yeremitech.mailplatform.domain.RecipientType;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class CreateBatchUseCase {
    private static final int MAX_RECIPIENTS_PER_REQUEST = 100;

    private final MailBatchRepository batchRepository;
    private final QueueEmailUseCase queueEmail;
    private final Clock clock;

    public CreateBatchUseCase(
            MailBatchRepository batchRepository,
            QueueEmailUseCase queueEmail,
            Clock clock) {
        this.batchRepository = batchRepository;
        this.queueEmail = queueEmail;
        this.clock = clock;
    }

    public Result execute(Command command) {
        if (command.recipients() == null || command.recipients().isEmpty()) {
            throw new IllegalArgumentException("at least one batch recipient is required");
        }
        if (command.recipients().size() > MAX_RECIPIENTS_PER_REQUEST) {
            throw new IllegalArgumentException("batch exceeds maximum recipients per request");
        }

        UUID batchId = UUID.randomUUID();
        Instant now = clock.instant();
        MailBatchData batch = new MailBatchData(
                batchId, command.clientId(), command.subject(), command.templateKey(),
                command.recipients().size(), BatchStatus.CREATED, now, now);
        batchRepository.save(batch);

        for (int i = 0; i < command.recipients().size(); i++) {
            Recipient recipient = command.recipients().get(i);
                Map<String, Object> variables = new HashMap<>();
                if (command.commonVariables() != null) {
                    variables.putAll(command.commonVariables());
                }
                if (recipient.variables() != null) {
                    variables.putAll(recipient.variables());
                }

                queueEmail.execute(new QueueEmailUseCase.Command(
                        command.clientId(),
                        "batch:" + batchId + ":" + i,
                        command.subject(),
                        command.templateKey(),
                        variables,
                        Map.of(),
                        null,
                        List.of(new MailRecipient(
                                new EmailAddress(recipient.email()),
                                RecipientType.TO,
                                recipient.displayName())),
                        command.attachmentIds(),
                        MailPriority.BULK,
                        batchId));
        }

        BatchStatus status = BatchStatus.QUEUED;
        MailBatchData updated = new MailBatchData(
                batch.id(), batch.clientId(), batch.subject(), batch.templateKey(),
                batch.totalRecipients(), status, batch.createdAt(), clock.instant());
        batchRepository.save(updated);
        return new Result(batchId, status, command.recipients().size(), 0);
    }

    public record Command(
            String clientId,
            String subject,
            String templateKey,
            Map<String, Object> commonVariables,
            List<UUID> attachmentIds,
            List<Recipient> recipients) {
    }

    public record Recipient(String email, String displayName, Map<String, Object> variables) {
    }

    public record Result(UUID batchId, BatchStatus status, int queued, int rejected) {
    }
}
