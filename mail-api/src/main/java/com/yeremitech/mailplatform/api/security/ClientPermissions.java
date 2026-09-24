package com.yeremitech.mailplatform.api.security;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** Central allow-list; do not persist misspelled or invented authorities. */
public final class ClientPermissions {
    public static final Set<String> ALL = Set.of(
            "EMAIL_SEND", "EMAIL_READ", "EMAIL_CANCEL", "ATTACHMENT_WRITE",
            "BATCH_READ", "BATCH_WRITE", "CAMPAIGN_READ", "CAMPAIGN_WRITE",
            "TEMPLATE_READ", "TEMPLATE_WRITE", "SUPPRESSION_READ", "SUPPRESSION_WRITE",
            "WEBHOOK_READ", "WEBHOOK_WRITE", "PASSWORD_RECOVERY", "DOCUMENT_SEND", "METRICS_READ");
    private ClientPermissions() {}

    public static Set<String> normalize(Set<String> requested, boolean allowWildcard) {
        if (requested == null || requested.isEmpty()) throw new IllegalArgumentException("at least one permission is required");
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String permission : requested) {
            if (permission == null) throw new IllegalArgumentException("invalid permission");
            String normalized = permission.trim().toUpperCase(Locale.ROOT);
            if ("*".equals(normalized)) {
                if (!allowWildcard) throw new IllegalArgumentException("new clients require explicit, least-privilege permissions");
            } else if (!ALL.contains(normalized)) {
                throw new IllegalArgumentException("unknown permission");
            }
            result.add(normalized);
        }
        if (result.contains("*") && result.size() != 1) throw new IllegalArgumentException("wildcard cannot be combined with explicit permissions");
        return Set.copyOf(result);
    }
}
