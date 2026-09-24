package com.yeremitech.mailplatform.api.config;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

/** A production startup must fail instead of silently accepting .env development defaults. */
@Configuration
@Profile("production")
public class ProductionSecurityConfiguration {
    private static final String[] REQUIRED = {
            "app.security.allow-legacy-keys", "app.docs.public",
            "app.attachments.antivirus.enabled", "app.attachments.antivirus.host",
            "spring.mail.properties.mail.smtp.starttls.enable",
            "spring.mail.properties.mail.smtp.starttls.required",
            "spring.mail.properties.mail.smtp.ssl.checkserveridentity",
            "app.security.api-key-pepper", "app.security.otp-hmac-secret",
            "app.security.admin-api-key", "app.security.sensitive-payload-key",
            "app.security.webhook-encryption-key", "spring.datasource.password",
            "spring.rabbitmq.username", "spring.rabbitmq.password",
            "app.marketing.public-base-url"
    };

    @Bean
    SmartInitializingSingleton productionSecurityGate(Environment environment) {
        return () -> {
            Map<String,String> values = new LinkedHashMap<>();
            for (String key : REQUIRED) values.put(key, environment.getProperty(key, ""));
            ProductionConfigurationValidator.validate(values);
        };
    }
}
