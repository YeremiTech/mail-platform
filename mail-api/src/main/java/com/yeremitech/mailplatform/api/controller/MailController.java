package com.yeremitech.mailplatform.api.controller;

import com.yeremitech.mailplatform.application.port.AttachmentStoragePort;
import com.yeremitech.mailplatform.api.security.ClientTemplatePolicy;
import com.yeremitech.mailplatform.application.port.DeliveryAttemptRepository;
import com.yeremitech.mailplatform.application.port.MailMessageRepository;
import com.yeremitech.mailplatform.application.usecase.QueueEmailUseCase;
import com.yeremitech.mailplatform.domain.EmailAddress;
import com.yeremitech.mailplatform.domain.MailPriority;
import com.yeremitech.mailplatform.domain.MailRecipient;
import com.yeremitech.mailplatform.domain.RecipientType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.Instant;
import java.util.NoSuchElementException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/emails")
public class MailController {
    private final QueueEmailUseCase queue;
    private final MailMessageRepository repository;
    private final DeliveryAttemptRepository attempts;
    private final AttachmentStoragePort attachments;
    private final ClientTemplatePolicy templatePolicy;
    private final com.yeremitech.mailplatform.api.service.MailCancellationService cancellation;

    public MailController(QueueEmailUseCase queue, MailMessageRepository repository, DeliveryAttemptRepository attempts, AttachmentStoragePort attachments, ClientTemplatePolicy templatePolicy, com.yeremitech.mailplatform.api.service.MailCancellationService cancellation) {
        this.queue = queue; this.repository = repository; this.attempts = attempts; this.attachments = attachments; this.templatePolicy = templatePolicy; this.cancellation = cancellation;
    }

    @PostMapping
    public ResponseEntity<?> send(Principal principal, @Valid @RequestBody SendMailRequest request,
                                  @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        String clientId = principal.getName();
        String pinnedTemplate = templatePolicy.resolveForQueue(clientId, request.templateKey());
        if (request.attachmentIds() != null) {
            for (UUID id : request.attachmentIds()) if (!attachments.belongsTo(clientId, id)) throw new IllegalArgumentException("attachment does not belong to authenticated client: " + id);
        }
        var recipients = request.recipients().stream()
                .map(item -> new MailRecipient(new EmailAddress(item.email()), item.type(), item.displayName())).toList();
        var result = queue.execute(new QueueEmailUseCase.Command(
                clientId, idempotencyKey, request.subject(), pinnedTemplate, request.variables(), Map.of(), null,
                recipients, request.attachmentIds(), request.priority() == null ? MailPriority.TRANSACTIONAL : request.priority(), null, request.scheduledAt()));
        return ResponseEntity.accepted().body(Map.of("id", result.id(), "status", result.status()));
    }

    @DeleteMapping("/{id}")
    public Object cancel(Principal principal, @PathVariable UUID id) {
        return cancellation.cancelOne(principal.getName(), id);
    }

    @GetMapping("/{id}")
    public Object status(Principal principal, @PathVariable UUID id) {
        var message = repository.findById(id).orElseThrow(() -> new NoSuchElementException("mail message not found"));
        if (!message.clientId().equals(principal.getName())) throw new NoSuchElementException("mail message not found");
        return message;
    }

    @GetMapping("/{id}/attempts")
    public Object attempts(Principal principal, @PathVariable UUID id) {
        var message = repository.findById(id).orElseThrow(() -> new NoSuchElementException("mail message not found"));
        if (!message.clientId().equals(principal.getName())) throw new NoSuchElementException("mail message not found");
        return attempts.findByMessageId(id);
    }

    public record RecipientDto(@Email @NotBlank String email, @NotNull RecipientType type, String displayName) {}
    public record SendMailRequest(@NotBlank String subject, @NotBlank String templateKey, Map<String, Object> variables,
                                  @NotEmpty List<@NotNull @Valid RecipientDto> recipients, List<@NotNull UUID> attachmentIds, MailPriority priority, Instant scheduledAt) {}
}
