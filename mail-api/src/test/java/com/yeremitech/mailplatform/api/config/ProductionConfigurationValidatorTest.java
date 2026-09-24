package com.yeremitech.mailplatform.api.config;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProductionConfigurationValidatorTest {
    private static Map<String,String> valid() {
        Map<String,String> values = new HashMap<>();
        values.put("app.security.allow-legacy-keys", "false");
        values.put("app.docs.public", "false");
        values.put("app.attachments.antivirus.enabled", "true");
        values.put("app.attachments.antivirus.host", "clamav.internal");
        for (String key : new String[]{"spring.mail.properties.mail.smtp.starttls.enable",
                "spring.mail.properties.mail.smtp.starttls.required",
                "spring.mail.properties.mail.smtp.ssl.checkserveridentity"}) values.put(key, "true");
        for (String key : new String[]{"app.security.api-key-pepper", "app.security.otp-hmac-secret",
                "app.security.admin-api-key"}) values.put(key, "a-unique-long-secret-32-bytes-or-more-" + key);
        byte[] payload = new byte[32];
        byte[] webhook = new byte[32];
        for (int i = 0; i < 32; i++) { payload[i] = (byte) (i + 1); webhook[i] = (byte) (i + 61); }
        values.put("app.security.sensitive-payload-key", Base64.getEncoder().encodeToString(payload));
        values.put("app.security.webhook-encryption-key", Base64.getEncoder().encodeToString(webhook));
        values.put("spring.datasource.password", "isolated-non-default-database-password");
        values.put("spring.rabbitmq.username", "mail-service");
        values.put("spring.rabbitmq.password", "isolated-non-default-broker-password");
        values.put("app.marketing.public-base-url", "https://mail.example.test");
        return values;
    }

    @Test void acceptsCompleteConfiguration() {
        assertDoesNotThrow(() -> ProductionConfigurationValidator.validate(valid()));
    }

    @Test void rejectsDevelopmentDefaultsAndBrokenTls() {
        for (String key : new String[]{"app.security.allow-legacy-keys", "app.docs.public",
                "app.attachments.antivirus.enabled", "spring.mail.properties.mail.smtp.starttls.enable",
                "spring.mail.properties.mail.smtp.starttls.required",
                "spring.mail.properties.mail.smtp.ssl.checkserveridentity"}) {
            Map<String,String> invalid = valid();
            invalid.put(key, key.equals("app.security.allow-legacy-keys")
                    || key.equals("app.docs.public") ? "true" : "false");
            assertThrows(IllegalStateException.class, () -> ProductionConfigurationValidator.validate(invalid), key);
        }
        Map<String,String> insecure = valid();
        insecure.put("spring.datasource.password", "mail_platform");
        assertThrows(IllegalStateException.class, () -> ProductionConfigurationValidator.validate(insecure));
    }

    @Test void rejectsCheckedInExampleSecretsAndReuse() {
        for (String key : new String[]{"app.security.api-key-pepper", "app.security.otp-hmac-secret",
                "app.security.admin-api-key"}) {
            Map<String,String> invalid = valid();
            invalid.put(key, "replace-with-independent-random-secret-32-bytes-minimum");
            assertThrows(IllegalStateException.class, () -> ProductionConfigurationValidator.validate(invalid));
        }
        Map<String,String> reused = valid();
        reused.put("app.security.admin-api-key", reused.get("app.security.api-key-pepper"));
        assertThrows(IllegalStateException.class, () -> ProductionConfigurationValidator.validate(reused));
        Map<String,String> badDb = valid();
        badDb.put("spring.datasource.password", "development-only-database-password");
        assertThrows(IllegalStateException.class, () -> ProductionConfigurationValidator.validate(badDb));
    }

    @Test void refusesUnsafeMarketingOriginAndWeakSecrets() {
        for (String origin : new String[]{"http://mail.example.test", "https://user:pass@mail.example.test",
                "https://mail.example.test/unsafe", "https://mail.example.test?secret=x"}) {
            Map<String,String> invalid = valid();
            invalid.put("app.marketing.public-base-url", origin);
            assertThrows(IllegalStateException.class, () -> ProductionConfigurationValidator.validate(invalid));
        }
        Map<String,String> invalid = valid();
        invalid.put("app.security.webhook-encryption-key", "not a key");
        assertThrows(IllegalStateException.class, () -> ProductionConfigurationValidator.validate(invalid));
        Map<String,String> reused = valid();
        reused.put("app.security.webhook-encryption-key", reused.get("app.security.sensitive-payload-key"));
        assertThrows(IllegalStateException.class, () -> ProductionConfigurationValidator.validate(reused));
    }
}
