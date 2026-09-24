package com.yeremitech.mailplatform.api.controller;

import com.yeremitech.mailplatform.api.security.ClientTemplatePolicy;
import com.yeremitech.mailplatform.api.service.CampaignService;
import com.yeremitech.mailplatform.api.service.CampaignCsvParser;
import com.yeremitech.mailplatform.api.service.MailCancellationService;
import com.yeremitech.mailplatform.application.port.AttachmentStoragePort;
import com.yeremitech.mailplatform.application.usecase.CreateBatchUseCase;
import com.yeremitech.mailplatform.application.usecase.GetBatchSummaryUseCase;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.io.IOException;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import com.yeremitech.mailplatform.domain.EmailAddress;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/campaigns")
public class CampaignController {
    private final CampaignService campaigns;
    private final GetBatchSummaryUseCase summary;
    private final MailCancellationService cancel;
    private final ClientTemplatePolicy policy;
    private final AttachmentStoragePort storage;
    public CampaignController(CampaignService campaigns,GetBatchSummaryUseCase summary,MailCancellationService cancel,
            ClientTemplatePolicy policy, AttachmentStoragePort storage){
        this.campaigns=campaigns;this.summary=summary;this.cancel=cancel;this.policy=policy;this.storage=storage;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Object create(Principal principal,@Valid @RequestBody Request body){
        String client=principal.getName();
        String pinned=policy.resolveForQueue(client,body.templateKey());
        if (body.attachmentIds()!=null) {
            if (body.attachmentIds().size()>10) throw new IllegalArgumentException("a campaign cannot exceed ten attachments");
            for(UUID id:body.attachmentIds()) if(!storage.belongsTo(client,id)) throw new IllegalArgumentException("attachment not found");
        }
        String purpose=body.purpose()==null?"TRANSACTIONAL":body.purpose();
        Set<String> consented=body.recipients().stream().filter(r->Boolean.TRUE.equals(r.consent()))
                .map(r->new EmailAddress(r.email()).value()).collect(Collectors.toSet());
        return campaigns.stage(client,body.subject(),pinned,body.commonVariables(),body.attachmentIds(),
                body.recipients().stream().map(r->new CreateBatchUseCase.Recipient(r.email(),r.displayName(),r.variables())).toList(),
                purpose,consented);
    }

    /** Multi-part upload: metadata is JSON, file is UTF-8 RFC-4180 CSV. */
    @PostMapping(path="/import-csv", consumes="multipart/form-data")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Object importCsv(Principal principal, @Valid @RequestPart("metadata") CsvMetadata data,
            @RequestPart("file") MultipartFile file) throws IOException {
        if (file.isEmpty() || file.getSize() > CampaignCsvParser.MAX_BYTES) {
            throw new IllegalArgumentException("CSV file must be between 1 byte and 5 MB");
        }
        if (data == null || data.subject() == null || data.subject().isBlank()
                || data.subject().length() > 300 || data.templateKey() == null || data.templateKey().isBlank()
                || data.subject().contains("\r") || data.subject().contains("\n")) {
            throw new IllegalArgumentException("CSV import requires a valid subject and templateKey");
        }
        if (!"MARKETING".equals(data.purpose()) && !"TRANSACTIONAL".equals(data.purpose())) {
            throw new IllegalArgumentException("purpose must be MARKETING or TRANSACTIONAL");
        }
        String client = principal.getName();
        String pinned = policy.resolveForQueue(client, data.templateKey());
        List<UUID> attachments = data.attachmentIds() == null ? List.of() : data.attachmentIds();
        if (attachments.size() > 10) throw new IllegalArgumentException("a campaign cannot exceed ten attachments");
        for (UUID id : attachments) if (id == null || !storage.belongsTo(client,id)) {
            throw new IllegalArgumentException("attachment not found");
        }
        List<CreateBatchUseCase.Recipient> recipients;
        try { recipients = CampaignCsvParser.parse(file.getInputStream(), "MARKETING".equals(data.purpose())); }
        catch (java.nio.charset.CharacterCodingException ex) {
            throw new IllegalArgumentException("CSV must contain valid UTF-8 text", ex);
        }
        Set<String> consented="MARKETING".equals(data.purpose())
                ? recipients.stream().map(r->new EmailAddress(r.email()).value()).collect(Collectors.toSet()) : Set.of();
        return campaigns.stage(client, data.subject(), pinned, data.commonVariables(), attachments,
                recipients,data.purpose(),consented);
    }

    @GetMapping("/{id}")
    public Object get(Principal principal,@PathVariable UUID id){
        var value=summary.execute(id);
        if(!value.batch().clientId().equals(principal.getName())) throw new NoSuchElementException("campaign not found");
        return Map.of("summary",value,"stagedRemaining",campaigns.stagedRemaining(principal.getName(),id),
                "suppressedRecipients",campaigns.suppressedCount(principal.getName(),id));
    }

    @DeleteMapping("/{id}")
    public Object cancel(Principal principal,@PathVariable UUID id){
        return cancel.cancelBatch(principal.getName(),id);
    }

    public record CsvMetadata(String subject, String templateKey, String purpose,
            Map<String,Object> commonVariables, List<UUID> attachmentIds) {}
    public record Recipient(@NotBlank @Email String email,String displayName,Map<String,Object> variables,Boolean consent){}
    public record Request(@NotBlank @Size(max=300) String subject,@NotBlank String templateKey,
            Map<String,Object> commonVariables,List<@NotNull UUID> attachmentIds,
            @NotEmpty @Size(max=10000) List<@NotNull @Valid Recipient> recipients,String purpose){}
}
