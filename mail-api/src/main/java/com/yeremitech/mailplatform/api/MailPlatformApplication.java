package com.yeremitech.mailplatform.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.yeremitech.mailplatform", exclude = UserDetailsServiceAutoConfiguration.class)
@EntityScan(basePackages = "com.yeremitech.mailplatform.infrastructure.jpa")
@EnableJpaRepositories(basePackages = "com.yeremitech.mailplatform.infrastructure.jpa")
@EnableScheduling
public class MailPlatformApplication {
    public static void main(String[] args) {
        SpringApplication.run(MailPlatformApplication.class, args);
    }
}
