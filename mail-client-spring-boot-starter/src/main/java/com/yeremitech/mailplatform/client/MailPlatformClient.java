package com.yeremitech.mailplatform.client;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import org.springframework.http.MediaType;
import org.springframework.core.io.Resource;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

public final class MailPlatformClient {
    private final RestClient client;

    public MailPlatformClient(String baseUrl, String clientId, String apiKey) {
        this(baseUrl, clientId, apiKey, Duration.ofSeconds(3), Duration.ofSeconds(15));
    }

    public MailPlatformClient(String baseUrl, String clientId, String apiKey,
                              Duration connectTimeout, Duration readTimeout) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);
        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .defaultHeader("X-Client-Id", clientId)
                .defaultHeader("X-Internal-Api-Key", apiKey)
                .build();
    }

    public PasswordRecoveryResponse requestPasswordReset(String subjectReference, String email) {
        return client.post().uri("/api/v1/password-recovery/challenges")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new PasswordRecoveryRequest(subjectReference, email))
                .retrieve().body(PasswordRecoveryResponse.class);
    }

    public VerifyRecoveryResponse verifyPasswordReset(UUID challengeId, String code) {
        return client.post().uri("/api/v1/password-recovery/challenges/{id}/verify", challengeId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new VerifyRecoveryRequest(code))
                .retrieve().body(VerifyRecoveryResponse.class);
    }

    public ConsumeGrantResponse consumeResetGrant(UUID grantId, String resetGrant) {
        return client.post().uri("/api/v1/password-recovery/grants/consume")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ConsumeGrantRequest(grantId, resetGrant))
                .retrieve().body(ConsumeGrantResponse.class);
    }

    public AttachmentResponse uploadAttachment(Resource file) {
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("file", file);
        return client.post().uri("/api/v1/attachments")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body.build()).retrieve().body(AttachmentResponse.class);
    }

    public SendMailResponse sendDocumentCopy(DocumentCopyRequest request, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key is required for document copies");
        }
        return client.post().uri("/api/v1/documents/copies")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request).retrieve().body(SendMailResponse.class);
    }

    public Map<String, Object> getEmailStatus(UUID id) {
        return client.get().uri("/api/v1/emails/{id}", id).retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public SendMailResponse sendEmail(SendMailRequest request, String idempotencyKey) {
        var spec = client.post().uri("/api/v1/emails").contentType(MediaType.APPLICATION_JSON);
        if (idempotencyKey != null && !idempotencyKey.isBlank()) spec.header("Idempotency-Key", idempotencyKey);
        return spec.body(request).retrieve().body(SendMailResponse.class);
    }


    public List<Map<String,Object>> getEmailAttempts(UUID id) {
        return client.get().uri("/api/v1/emails/{id}/attempts", id).retrieve()
                .body(new ParameterizedTypeReference<List<Map<String,Object>>>() {});
    }

    public Map<String,Object> cancelEmail(UUID id) {
        return client.delete().uri("/api/v1/emails/{id}", id).retrieve()
                .body(new ParameterizedTypeReference<Map<String,Object>>() {});
    }

    public BatchResponse createBatch(BatchRequest request) {
        return client.post().uri("/api/v1/batches").contentType(MediaType.APPLICATION_JSON)
                .body(request).retrieve().body(BatchResponse.class);
    }

    public Map<String,Object> getBatch(UUID id) {
        return client.get().uri("/api/v1/batches/{id}", id).retrieve()
                .body(new ParameterizedTypeReference<Map<String,Object>>() {});
    }

    public Map<String,Object> cancelBatch(UUID id) {
        return client.delete().uri("/api/v1/batches/{id}", id).retrieve()
                .body(new ParameterizedTypeReference<Map<String,Object>>() {});
    }

    public Map<String,Object> createTemplate(String slug, TemplateBody body) {
        return client.post().uri("/api/v1/templates/{slug}", slug).contentType(MediaType.APPLICATION_JSON)
                .body(body).retrieve().body(new ParameterizedTypeReference<Map<String,Object>>() {});
    }

    public Map<String,Object> reviseTemplate(String slug, TemplateBody body) {
        return client.post().uri("/api/v1/templates/{slug}/versions", slug).contentType(MediaType.APPLICATION_JSON)
                .body(body).retrieve().body(new ParameterizedTypeReference<Map<String,Object>>() {});
    }

    public Map<String,Object> publishTemplate(String slug, int version) {
        return client.post().uri("/api/v1/templates/{slug}/versions/{version}/publish", slug, version)
                .retrieve().body(new ParameterizedTypeReference<Map<String,Object>>() {});
    }

    public List<Map<String,Object>> listTemplates() {
        return client.get().uri("/api/v1/templates").retrieve()
                .body(new ParameterizedTypeReference<List<Map<String,Object>>>() {});
    }

    public Map<String,Object> stageCampaign(BatchRequest request) {
        return client.post().uri("/api/v1/campaigns").contentType(MediaType.APPLICATION_JSON)
                .body(request).retrieve().body(new ParameterizedTypeReference<Map<String,Object>>() {});
    }

    /** Import a 5 MB max UTF-8 CSV; header begins with email,display_name, then var_name etc. */
    public Map<String,Object> importCampaignCsv(Resource csv, CsvImportMetadata metadata) {
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("metadata", metadata).contentType(MediaType.APPLICATION_JSON);
        body.part("file", csv).contentType(MediaType.parseMediaType("text/csv"));
        return client.post().uri("/api/v1/campaigns/import-csv")
                .contentType(MediaType.MULTIPART_FORM_DATA).body(body.build())
                .retrieve().body(new ParameterizedTypeReference<Map<String,Object>>() {});
    }

    /** Marketing campaigns require consent=true for each recipient. */
    public Map<String,Object> stageMarketingCampaign(MarketingCampaignRequest request) {
        return client.post().uri("/api/v1/campaigns").contentType(MediaType.APPLICATION_JSON)
                .body(request).retrieve().body(new ParameterizedTypeReference<Map<String,Object>>() {});
    }

    /** Persist a tenant-specific opt-out; requests are idempotent. */
    public SuppressionResponse suppressRecipient(String email, SuppressionReason reason) {
        return client.post().uri("/api/v1/suppressions").contentType(MediaType.APPLICATION_JSON)
                .body(new SuppressionRequest(email,reason.name())).retrieve().body(SuppressionResponse.class);
    }

    public List<SuppressionDetail> getSuppressions(int limit) {
        return client.get().uri(builder->builder.path("/api/v1/suppressions")
                        .queryParam("limit",limit).build())
                .retrieve().body(new ParameterizedTypeReference<List<SuppressionDetail>>() {});
    }

    /** Remove only after the consumer has verified fresh recipient consent. */
    public void removeSuppression(String email) {
        client.delete().uri(builder->builder.path("/api/v1/suppressions")
                .queryParam("email",email).build()).retrieve().toBodilessEntity();
    }

    public Map<String,Object> getCampaign(UUID id) {
        return client.get().uri("/api/v1/campaigns/{id}", id).retrieve()
                .body(new ParameterizedTypeReference<Map<String,Object>>() {});
    }

    public Map<String,Object> cancelCampaign(UUID id) {
        return client.delete().uri("/api/v1/campaigns/{id}", id).retrieve()
                .body(new ParameterizedTypeReference<Map<String,Object>>() {});
    }

    /** Signing secret appears only in this response; store it in the consumer's secret manager. */
    public WebhookCreated configureWebhook(String httpsEndpoint) {
        return client.put().uri("/api/v1/webhooks/subscription").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("endpoint",httpsEndpoint)).retrieve().body(WebhookCreated.class);
    }

    public Map<String,Object> webhookSubscription() {
        return client.get().uri("/api/v1/webhooks/subscription").retrieve()
                .body(new ParameterizedTypeReference<Map<String,Object>>() {});
    }

    public List<Map<String,Object>> webhookDeliveries() {
        return client.get().uri("/api/v1/webhooks/deliveries").retrieve()
                .body(new ParameterizedTypeReference<List<Map<String,Object>>>() {});
    }

    public void disableWebhook() {
        client.delete().uri("/api/v1/webhooks/subscription").retrieve().toBodilessEntity();
    }

    public enum SuppressionReason { UNSUBSCRIBED, COMPLAINT, BOUNCE, MANUAL }
    public record SuppressionRequest(String email,String reason) {}
    public record SuppressionResponse(String email,String reason) {}
    public record SuppressionDetail(String email,String reason,Instant createdAt,Instant updatedAt) {}
    public record MarketingRecipient(String email,String displayName,Map<String,Object> variables,boolean consent) {}
    public record MarketingCampaignRequest(String subject,String templateKey,Map<String,Object> commonVariables,
            List<UUID> attachmentIds,List<MarketingRecipient> recipients,String purpose) {
        public MarketingCampaignRequest(String subject,String templateKey,Map<String,Object> commonVariables,
                List<UUID> attachmentIds,List<MarketingRecipient> recipients) {
            this(subject,templateKey,commonVariables,attachmentIds,recipients,"MARKETING");
        }
    }

    public record CsvImportMetadata(String subject, String templateKey, String purpose,
            Map<String,Object> commonVariables, List<UUID> attachmentIds) {}
    public record WebhookCreated(String endpoint,String signingSecret,boolean enabled) {}
    public record BatchRecipient(String email, String displayName, Map<String,Object> variables) {}
    public record BatchRequest(String subject, String templateKey, Map<String,Object> commonVariables,
            List<UUID> attachmentIds, List<BatchRecipient> recipients) {}
    public record BatchResponse(UUID batchId, String status, int queued, int rejected) {}
    public record TemplateBody(String html, String text, List<String> requiredVariables) {}

    public record PasswordRecoveryRequest(String subjectReference, String email) {}
    public record PasswordRecoveryResponse(UUID challengeId, String expiresAt) {}
    public record VerifyRecoveryRequest(String code) {}
    public record VerifyRecoveryResponse(UUID grantId, String resetGrant, String expiresAt) {}
    public record ConsumeGrantRequest(UUID grantId, String resetGrant) {}
    public record ConsumeGrantResponse(String clientId, String subjectReference) {}
    public record AttachmentResponse(UUID id, String clientId, String filename, String contentType,
                                     long sizeBytes, String storageKey, String checksumSha256) {}
    public record Recipient(String email, String type, String displayName) {}
    public record SendMailRequest(String subject, String templateKey, Map<String,Object> variables,
            List<Recipient> recipients, List<UUID> attachmentIds, String priority, Instant scheduledAt) {
        public SendMailRequest(String subject, String templateKey, Map<String,Object> variables,
                List<Recipient> recipients, List<UUID> attachmentIds, String priority) {
            this(subject, templateKey, variables, recipients, attachmentIds, priority, null);
        }
    }
    public record SendMailResponse(UUID id, String status) {}
    public record DocumentRecipient(String email, String displayName) {}
    public record DocumentCopyRequest(String documentType, String documentNumber, String customerName,
                                      String issuerName, BigDecimal amount, String currency,
                                      List<DocumentRecipient> recipients, List<UUID> attachmentIds) {}
}
