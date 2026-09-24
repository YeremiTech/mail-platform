package com.yeremitech.mailplatform.client.autoconfigure;

import com.yeremitech.mailplatform.client.MailPlatformClient;
import com.yeremitech.mailplatform.client.MailPlatformProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnProperty(prefix = "mail-platform", name = {"base-url", "client-id", "api-key"})
@EnableConfigurationProperties(MailPlatformProperties.class)
public class MailPlatformAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    MailPlatformClient mailPlatformClient(MailPlatformProperties properties) {
        return new MailPlatformClient(properties.baseUrl(), properties.clientId(), properties.apiKey(),
                properties.connectTimeout(), properties.readTimeout());
    }
}
