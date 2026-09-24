package com.yeremitech.mailplatform.api.controller;

import com.yeremitech.mailplatform.application.port.AttachmentStoragePort;
import com.yeremitech.mailplatform.api.security.ClientTemplatePolicy;
import com.yeremitech.mailplatform.application.usecase.CreateBatchUseCase;
import com.yeremitech.mailplatform.api.service.BatchSubmissionService;
import com.yeremitech.mailplatform.application.usecase.GetBatchSummaryUseCase;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.NoSuchElementException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/batches")
public class BatchController {
    private final BatchSubmissionService createBatch;
    private final GetBatchSummaryUseCase getBatchSummary;
    private final AttachmentStoragePort attachments;
    private final ClientTemplatePolicy templatePolicy;
    private final com.yeremitech.mailplatform.api.service.MailCancellationService cancellation;

    public BatchController(BatchSubmissionService createBatch, GetBatchSummaryUseCase getBatchSummary, AttachmentStoragePort attachments, ClientTemplatePolicy templatePolicy,
            com.yeremitech.mailplatform.api.service.MailCancellationService cancellation) {
        this.createBatch = createBatch; this.getBatchSummary = getBatchSummary; this.attachments = attachments; this.templatePolicy = templatePolicy; this.cancellation = cancellation;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Object create(Principal principal, @Valid @RequestBody BatchRequest request) {
        String pinnedTemplate = templatePolicy.resolveForQueue(principal.getName(), request.templateKey());
        if (request.attachmentIds() != null) {
            for (UUID id : request.attachmentIds()) if (!attachments.belongsTo(principal.getName(), id)) throw new IllegalArgumentException("attachment does not belong to authenticated client: " + id);
        }
        List<CreateBatchUseCase.Recipient> recipients = request.recipients().stream()
                .map(r -> new CreateBatchUseCase.Recipient(r.email(), r.displayName(), r.variables())).toList();
        return createBatch.submit(new CreateBatchUseCase.Command(
                principal.getName(), request.subject(), pinnedTemplate, request.commonVariables(), request.attachmentIds(), recipients));
    }

    @DeleteMapping("/{id}")
    public Object cancel(Principal principal, @PathVariable UUID id) {
        return cancellation.cancelBatch(principal.getName(), id);
    }

    @GetMapping("/{id}")
    public Object get(Principal principal, @PathVariable UUID id) {
        var result = getBatchSummary.execute(id);
        if (!result.batch().clientId().equals(principal.getName())) throw new NoSuchElementException("batch not found");
        return result;
    }

    public record BatchRecipient(@Email @NotBlank String email, String displayName, Map<String, Object> variables) {}
    public record BatchRequest(@NotBlank String subject, @NotBlank String templateKey, Map<String, Object> commonVariables,
                               List<@NotNull UUID> attachmentIds, @NotEmpty @Size(max = 100) List<@NotNull @Valid BatchRecipient> recipients) {}
}
