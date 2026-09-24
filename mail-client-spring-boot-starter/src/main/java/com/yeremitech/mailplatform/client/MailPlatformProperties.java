package com.yeremitech.mailplatform.client;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

@ConfigurationProperties("mail-platform")
public record MailPlatformProperties(String baseUrl, String clientId, String apiKey,
                                     Duration connectTimeout, Duration readTimeout) {
    public MailPlatformProperties {
        if (baseUrl == null || baseUrl.isBlank()) throw new IllegalArgumentException("mail-platform.base-url is required");
        if (clientId == null || clientId.isBlank()) throw new IllegalArgumentException("mail-platform.client-id is required");
        if (apiKey == null || apiKey.length() < 32) throw new IllegalArgumentException("mail-platform.api-key must contain at least 32 characters");
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(3) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(15) : readTimeout;
        if (connectTimeout.isNegative() || connectTimeout.isZero() || readTimeout.isNegative() || readTimeout.isZero()) {
            throw new IllegalArgumentException("mail-platform HTTP timeouts must be positive");
        }
    }

    public MailPlatformProperties(String baseUrl, String clientId, String apiKey) {
        this(baseUrl, clientId, apiKey, null, null);
    }
}
