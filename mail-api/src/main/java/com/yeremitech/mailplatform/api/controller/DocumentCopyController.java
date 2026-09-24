package com.yeremitech.mailplatform.api.controller;

import com.yeremitech.mailplatform.application.port.AttachmentStoragePort;
import com.yeremitech.mailplatform.application.usecase.QueueEmailUseCase;
import com.yeremitech.mailplatform.domain.EmailAddress;
import com.yeremitech.mailplatform.domain.MailPriority;
import com.yeremitech.mailplatform.domain.MailRecipient;
import com.yeremitech.mailplatform.domain.RecipientType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.math.BigDecimal;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/documents/copies")
public class DocumentCopyController {
    private final QueueEmailUseCase queue;
    private final AttachmentStoragePort attachments;

    public DocumentCopyController(QueueEmailUseCase queue, AttachmentStoragePort attachments) {
        this.queue = queue;
        this.attachments = attachments;
    }

    @PostMapping
    public ResponseEntity<?> send(Principal principal, @RequestHeader("Idempotency-Key") String idempotencyKey,
                                  @Valid @RequestBody CopyRequest request) {
        if (idempotencyKey.isBlank()) throw new IllegalArgumentException("Idempotency-Key is required");
        String clientId = principal.getName();
        boolean hasPdf = false;
        for (UUID id : request.attachmentIds()) {
            var stored = attachments.load(clientId, id)
                    .orElseThrow(() -> new IllegalArgumentException("attachment not found for authenticated client"));
            try (var content = stored.content()) {
                if ("application/pdf".equals(stored.metadata().contentType())) {
                    byte[] header = content.readNBytes(5);
                    if (header.length != 5 || header[0] != '%' || header[1] != 'P' || header[2] != 'D'
                            || header[3] != 'F' || header[4] != '-') {
                        throw new IllegalArgumentException("PDF attachment has invalid content");
                    }
                    hasPdf = true;
                }
            } catch (IOException ex) {
                throw new IllegalStateException("unable to read attachment", ex);
            }
        }
        if (!hasPdf) throw new IllegalArgumentException("a PDF attachment is required");

        String documentType = request.documentType().name().toLowerCase();
        var recipients = request.recipients().stream()
                .map(r -> new MailRecipient(new EmailAddress(r.email()), RecipientType.TO, r.displayName()))
                .toList();
        var result = queue.execute(new QueueEmailUseCase.Command(
                clientId, idempotencyKey,
                "Duplicado de " + documentType + " " + request.documentNumber(),
                "billing/document-copy",
                Map.of("documentType", documentType, "documentNumber", request.documentNumber(),
                        "customerName", request.customerName(), "issuerName", request.issuerName(),
                        "amount", request.amount().toPlainString(), "currency", request.currency()),
                Map.of(), null, recipients, request.attachmentIds(), MailPriority.DOCUMENT, null));
        return ResponseEntity.accepted().body(Map.of("id", result.id(), "status", result.status()));
    }

    public enum DocumentType { FACTURA, BOLETA }
    public record RecipientDto(@NotBlank @Email String email, @Size(max=200) String displayName) {}
    public record CopyRequest(
            @NotNull DocumentType documentType,
            @NotBlank @Size(max=50) @Pattern(regexp="[A-Za-z0-9][A-Za-z0-9/_-]*") String documentNumber,
            @NotBlank @Size(max=200) String customerName,
            @NotBlank @Size(max=200) String issuerName,
            @NotNull @DecimalMin("0.00") @Digits(integer=12, fraction=2) BigDecimal amount,
            @NotBlank @Pattern(regexp="[A-Z]{3}") String currency,
            @NotEmpty @Size(max=100) List<@Valid RecipientDto> recipients,
            @NotEmpty @Size(max=10) List<@NotNull UUID> attachmentIds) {}
}
