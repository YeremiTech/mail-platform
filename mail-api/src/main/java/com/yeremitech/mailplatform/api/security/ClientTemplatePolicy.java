package com.yeremitech.mailplatform.api.security;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.access.AccessDeniedException;

/** Explicit template grants for the general mail and batch endpoints. */
public final class ClientTemplatePolicy {
    private final Map<String, Set<String>> grants;
    private final com.yeremitech.mailplatform.api.service.DynamicTemplateService templates;

    public ClientTemplatePolicy(String configuration) { this(configuration, null); }

    public ClientTemplatePolicy(String configuration,
            com.yeremitech.mailplatform.api.service.DynamicTemplateService templates) {
        this.templates = templates;
        Map<String, Set<String>> parsed = new HashMap<>();
        if (configuration != null && !configuration.isBlank()) {
            for (String entry : configuration.split(",")) {
                String[] parts = entry.trim().split("=", 2);
                if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
                    throw new IllegalArgumentException("invalid CLIENT_TEMPLATE_ACCESS entry");
                }
                Set<String> templates = Arrays.stream(parts[1].split("\\|"))
                        .map(String::trim).filter(s -> !s.isBlank()).collect(Collectors.toUnmodifiableSet());
                for (String template : templates) {
                    if (!template.matches("[a-z0-9][a-z0-9/_-]{0,199}")
                            || template.startsWith("security/") || "billing/document-copy".equals(template)
                            || !new ClassPathResource("templates/mail/" + template + ".html").exists()) {
                        throw new IllegalArgumentException("CLIENT_TEMPLATE_ACCESS references an unavailable template");
                    }
                }
                if (templates.isEmpty() || parsed.putIfAbsent(parts[0], templates) != null) {
                    throw new IllegalArgumentException("invalid or duplicate CLIENT_TEMPLATE_ACCESS entry");
                }
            }
        }
        grants = Map.copyOf(parsed);
    }

    public void assertAllowed(String clientId, String templateKey) {
        resolveForQueue(clientId, templateKey);
    }

    /** Resolve a tenant-owned alias to an immutable version before persisting a message. */
    public String resolveForQueue(String clientId, String templateKey) {
        if (templateKey != null && templateKey.matches("custom/[a-z0-9][a-z0-9_-]{0,79}")) {
            if (templates == null) throw new AccessDeniedException("dynamic templates are unavailable");
            return templates.pinPublished(clientId, templateKey.substring("custom/".length()));
        }
        if (templateKey == null || templateKey.startsWith("security/")
                || "billing/document-copy".equals(templateKey)
                || !grants.getOrDefault(clientId, Set.of()).contains(templateKey)) {
            throw new AccessDeniedException("template is not available to authenticated client");
        }
        return templateKey;
    }
}
