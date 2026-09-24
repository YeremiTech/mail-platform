package com.yeremitech.mailplatform.api.config;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import java.util.Locale;

/** Pure checks of deployment security invariants; does not log secret values. */
public final class ProductionConfigurationValidator {
    private ProductionConfigurationValidator() {}

    public static void validate(Map<String,String> values) {
        expect(values, "app.security.allow-legacy-keys", "false");
        expect(values, "app.docs.public", "false");
        expect(values, "app.attachments.antivirus.enabled", "true");
        expect(values, "spring.mail.properties.mail.smtp.starttls.enable", "true");
        expect(values, "spring.mail.properties.mail.smtp.starttls.required", "true");
        expect(values, "spring.mail.properties.mail.smtp.ssl.checkserveridentity", "true");
        String[] textSecrets = {"app.security.api-key-pepper", "app.security.otp-hmac-secret",
                "app.security.admin-api-key"};
        for (int i = 0; i < textSecrets.length; i++) {
            String key = textSecrets[i];
            String secret = values.get(key);
            if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32
                    || isObviousPlaceholder(secret)) {
                throw new IllegalStateException(key + " must be a distinct non-example secret of at least 32 bytes");
            }
            for (int previous = 0; previous < i; previous++) {
                if (MessageDigest.isEqual(secret.getBytes(StandardCharsets.UTF_8),
                        values.get(textSecrets[previous]).getBytes(StandardCharsets.UTF_8))) {
                    throw new IllegalStateException(key + " must not reuse a different authentication secret");
                }
            }
        }
        byte[] payloadKey = decodeKey(values, "app.security.sensitive-payload-key");
        byte[] webhookKey = decodeKey(values, "app.security.webhook-encryption-key");
        if (MessageDigest.isEqual(payloadKey, webhookKey)) {
            throw new IllegalStateException("sensitive payload and webhook encryption keys must be independent");
        }
        if (values.getOrDefault("app.attachments.antivirus.host", "").isBlank()) {
            throw new IllegalStateException("app.attachments.antivirus.host is mandatory in production");
        }
        String databasePassword = values.getOrDefault("spring.datasource.password", "");
        if (databasePassword.isBlank() || databasePassword.equals("mail_platform")
                || isObviousPlaceholder(databasePassword)) {
            throw new IllegalStateException("spring.datasource.password cannot use the development default");
        }
        if (values.getOrDefault("spring.rabbitmq.username", "guest").equals("guest")
                || values.getOrDefault("spring.rabbitmq.password", "guest").equals("guest")
                || values.getOrDefault("spring.rabbitmq.password", "").length() < 16
                || isObviousPlaceholder(values.getOrDefault("spring.rabbitmq.password", ""))) {
            throw new IllegalStateException("production RabbitMQ must use a dedicated non-default account");
        }
        String publicOrigin = values.getOrDefault("app.marketing.public-base-url", "");
        if (!publicOrigin.isBlank()) {
            try {
                URI uri = URI.create(publicOrigin);
                if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                        || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                        || uri.getRawFragment() != null || !uri.getPath().replace("/", "").isEmpty()) {
                    throw new IllegalArgumentException("origin");
                }
            } catch (IllegalArgumentException error) {
                throw new IllegalStateException("app.marketing.public-base-url must be a HTTPS origin without path, query or credentials");
            }
        }
    }

    private static boolean isObviousPlaceholder(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.contains("change-this") || normalized.contains("replace-with")
                || normalized.contains("development-only") || normalized.contains("example-secret")
                || normalized.contains("test-password") || normalized.contains("your-secret-here");
    }

    private static byte[] decodeKey(Map<String,String> values, String name) {
        try {
            byte[] secret = Base64.getDecoder().decode(values.getOrDefault(name, ""));
            if (secret.length != 32) throw new IllegalArgumentException("length");
            // Reject the common all-zero demo key and trivially repeated bytes.
            boolean repeated = true;
            for (int i = 1; i < secret.length; i++) repeated &= secret[i] == secret[0];
            if (repeated) throw new IllegalArgumentException("placeholder");
            return secret;
        } catch (IllegalArgumentException error) {
            throw new IllegalStateException(name + " must be Base64 of 32 non-placeholder random bytes");
        }
    }

    private static void expect(Map<String,String> values, String key, String required) {
        if (!required.equalsIgnoreCase(values.getOrDefault(key, ""))) {
            throw new IllegalStateException("unsafe production setting: " + key + " must equal " + required);
        }
    }
}
