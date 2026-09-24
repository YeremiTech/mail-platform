package com.yeremitech.mailplatform.api.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.beans.factory.annotation.Value;

/** Both headers are required together; do not document the API key as a browser token. */
@Configuration
@PropertySource("classpath:mail-platform-version.properties")
public class OpenApiConfiguration {
    @Bean
    OpenAPI mailPlatformOpenApi(@Value("${app.api.version}") String artifactVersion) {
        return new OpenAPI()
                .info(new Info().title("Mail Platform API").version(artifactVersion)
                        .description("Multi-tenant REST email platform. SENT means accepted by SMTP, not final delivery."))
                .components(new Components()
                        .addSecuritySchemes("clientId", new SecurityScheme().type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER).name("X-Client-Id"))
                        .addSecuritySchemes("clientApiKey", new SecurityScheme().type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER).name("X-Internal-Api-Key")))
                .addSecurityItem(new SecurityRequirement().addList("clientId").addList("clientApiKey"));
    }
}
