package com.yeremitech.mailplatform.api.controller;

import com.yeremitech.mailplatform.api.security.PersistentClientCredentials;
import com.yeremitech.mailplatform.api.service.AdminAuditService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** Bootstrap administration requires the independent ADMIN_API_KEY and X-Client-Id: admin. */
@RestController
@RequestMapping("/api/v1/admin/clients")
public class ClientAdminController {
    private final PersistentClientCredentials credentials;
    private final AdminAuditService audit;
    public ClientAdminController(PersistentClientCredentials credentials, AdminAuditService audit) {
        this.credentials = credentials; this.audit = audit;
    }

    @GetMapping
    public Object list() { return credentials.clients(); }

    @PostMapping
    @Transactional
    @ResponseStatus(HttpStatus.CREATED)
    public Object create(@Valid @RequestBody NewClient body) {
        var created = credentials.createClient(body.clientId(), body.displayName(), body.requestsPerMinute(),
                ttl(body.ttlDays()), body.permissions());
        audit.record("CLIENT_CREATED", body.clientId(), created.credentialId(), "displayName,requestsPerMinute,permissions");
        return created;
    }

    @PostMapping("/{clientId}/credentials")
    @Transactional
    @ResponseStatus(HttpStatus.CREATED)
    public Object rotate(@PathVariable String clientId, @Valid @RequestBody NewCredential body) {
        var created = credentials.issueKey(clientId, ttl(body.ttlDays()));
        audit.record("CREDENTIAL_ISSUED", clientId, created.credentialId(), null);
        return created;
    }

    @GetMapping("/{clientId}/credentials")
    public Object keys(@PathVariable String clientId) { return credentials.credentials(clientId); }

    @DeleteMapping("/{clientId}/credentials/{credentialId}")
    @Transactional
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable String clientId, @PathVariable UUID credentialId) {
        credentials.revoke(clientId, credentialId);
        audit.record("CREDENTIAL_REVOKED", clientId, credentialId, null);
    }

    @PatchMapping("/{clientId}")
    @Transactional
    public Object update(@PathVariable String clientId, @Valid @RequestBody UpdateClient body) {
        if (body.enabled() == null && body.requestsPerMinute() == null && body.webhookAllowedHost() == null
                && body.dailyTransactionalLimit()==null && body.dailyBulkLimit()==null && body.permissions()==null) throw new IllegalArgumentException("no changes supplied");
        if (body.enabled() != null) credentials.setEnabled(clientId, body.enabled());
        if (body.requestsPerMinute() != null) credentials.setQuota(clientId, body.requestsPerMinute());
        if (body.webhookAllowedHost() != null) credentials.setWebhookAllowedHost(clientId, body.webhookAllowedHost());
        credentials.setDailyLimits(clientId,body.dailyTransactionalLimit(),body.dailyBulkLimit());
        if (body.permissions()!=null) credentials.setPermissions(clientId, body.permissions());
        List<String> fields = new ArrayList<>();
        if (body.enabled()!=null) fields.add("enabled");
        if (body.requestsPerMinute()!=null) fields.add("requestsPerMinute");
        if (body.webhookAllowedHost()!=null) fields.add("webhookAllowedHost");
        if (body.dailyTransactionalLimit()!=null) fields.add("dailyTransactionalLimit");
        if (body.dailyBulkLimit()!=null) fields.add("dailyBulkLimit");
        if (body.permissions()!=null) fields.add("permissions");
        audit.record("CLIENT_UPDATED", clientId, null, String.join(",",fields));
        return Map.of("clientId", clientId, "updated", true);
    }

    @GetMapping("/audit")
    public Object audit(@RequestParam(defaultValue="50") int limit) { return audit.recent(limit); }

    private static Duration ttl(Integer days) {
        return days == null ? null : Duration.ofDays(days);
    }

    public record NewClient(@NotBlank @Pattern(regexp="[A-Za-z0-9._-]{1,120}") String clientId,
            @NotBlank @Size(max=200) String displayName,
            @Min(1) @Max(10000) int requestsPerMinute,
            @Min(1) @Max(730) Integer ttlDays,
            @NotEmpty Set<String> permissions) {}
    public record NewCredential(@Min(1) @Max(730) Integer ttlDays) {}
    public record UpdateClient(Boolean enabled, @Min(1) @Max(10000) Integer requestsPerMinute,
            String webhookAllowedHost,@Min(1) @Max(1000000) Integer dailyTransactionalLimit,
            @Min(1) @Max(1000000) Integer dailyBulkLimit, Set<String> permissions) {}
}
