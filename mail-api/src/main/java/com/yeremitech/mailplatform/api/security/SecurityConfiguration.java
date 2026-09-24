package com.yeremitech.mailplatform.api.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfiguration {
    @Bean
    SecurityFilterChain filterChain(
            HttpSecurity http,
            @Value("${app.security.internal-api-keys:}") String apiKeys,
            @Value("${app.security.admin-api-key:}") String adminKey,
            @Value("${app.security.allow-legacy-keys:true}") boolean allowLegacyKeys,
            PersistentClientCredentials credentials,
            @Value("${app.docs.public:false}") boolean publicDocs) throws Exception {
        return http
                .csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((request, response, ex) -> {
                            response.setStatus(401);
                            response.setContentType("application/json");
                            response.getWriter().write("{\"code\":\"UNAUTHORIZED\",\"message\":\"Valid client credentials are required\"}");
                        })
                        .accessDeniedHandler((request, response, ex) -> {
                            response.setStatus(403);
                            response.setContentType("application/json");
                            response.getWriter().write("{\"code\":\"FORBIDDEN\",\"message\":\"Access denied\"}");
                        }))
                .authorizeHttpRequests(a -> {
                    a.requestMatchers("/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness").permitAll();
                    if (publicDocs) a.requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll();
                    else a.requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").hasAnyRole("INTERNAL","ADMIN");
                    a.requestMatchers("/api/v1/public/unsubscribe", "/api/v1/public/unsubscribe/one-click").permitAll();
                    a.requestMatchers("/api/v1/admin/**").hasRole("ADMIN");
                    a.requestMatchers("/actuator/info", "/actuator/metrics", "/actuator/metrics/**", "/actuator/prometheus")
                            .hasAnyAuthority("PERM_METRICS_READ", "ROLE_ADMIN");
                    a.requestMatchers(HttpMethod.POST, "/api/v1/emails").hasAnyAuthority("PERM_ALL", "PERM_EMAIL_SEND");
                    a.requestMatchers(HttpMethod.GET, "/api/v1/emails/**").hasAnyAuthority("PERM_ALL", "PERM_EMAIL_READ");
                    a.requestMatchers(HttpMethod.DELETE, "/api/v1/emails/**").hasAnyAuthority("PERM_ALL", "PERM_EMAIL_CANCEL");
                    a.requestMatchers(HttpMethod.POST, "/api/v1/attachments").hasAnyAuthority("PERM_ALL", "PERM_ATTACHMENT_WRITE");
                    a.requestMatchers(HttpMethod.GET, "/api/v1/batches/**").hasAnyAuthority("PERM_ALL", "PERM_BATCH_READ");
                    a.requestMatchers(HttpMethod.POST, "/api/v1/batches").hasAnyAuthority("PERM_ALL", "PERM_BATCH_WRITE");
                    a.requestMatchers(HttpMethod.DELETE, "/api/v1/batches/**").hasAnyAuthority("PERM_ALL", "PERM_BATCH_WRITE");
                    a.requestMatchers(HttpMethod.GET, "/api/v1/campaigns/**").hasAnyAuthority("PERM_ALL", "PERM_CAMPAIGN_READ");
                    a.requestMatchers(HttpMethod.POST, "/api/v1/campaigns/**").hasAnyAuthority("PERM_ALL", "PERM_CAMPAIGN_WRITE");
                    a.requestMatchers(HttpMethod.DELETE, "/api/v1/campaigns/**").hasAnyAuthority("PERM_ALL", "PERM_CAMPAIGN_WRITE");
                    a.requestMatchers(HttpMethod.GET, "/api/v1/templates/**").hasAnyAuthority("PERM_ALL", "PERM_TEMPLATE_READ");
                    a.requestMatchers(HttpMethod.POST, "/api/v1/templates/**").hasAnyAuthority("PERM_ALL", "PERM_TEMPLATE_WRITE");
                    a.requestMatchers(HttpMethod.DELETE, "/api/v1/templates/**").hasAnyAuthority("PERM_ALL", "PERM_TEMPLATE_WRITE");
                    a.requestMatchers(HttpMethod.GET, "/api/v1/suppressions").hasAnyAuthority("PERM_ALL", "PERM_SUPPRESSION_READ");
                    a.requestMatchers(HttpMethod.POST, "/api/v1/suppressions").hasAnyAuthority("PERM_ALL", "PERM_SUPPRESSION_WRITE");
                    a.requestMatchers(HttpMethod.DELETE, "/api/v1/suppressions").hasAnyAuthority("PERM_ALL", "PERM_SUPPRESSION_WRITE");
                    a.requestMatchers(HttpMethod.GET, "/api/v1/webhooks/**").hasAnyAuthority("PERM_ALL", "PERM_WEBHOOK_READ");
                    a.requestMatchers(HttpMethod.PUT, "/api/v1/webhooks/**").hasAnyAuthority("PERM_ALL", "PERM_WEBHOOK_WRITE");
                    a.requestMatchers(HttpMethod.DELETE, "/api/v1/webhooks/**").hasAnyAuthority("PERM_ALL", "PERM_WEBHOOK_WRITE");
                    a.requestMatchers(HttpMethod.POST, "/api/v1/password-recovery/**").hasAnyAuthority("PERM_ALL", "PERM_PASSWORD_RECOVERY");
                    a.requestMatchers(HttpMethod.POST, "/api/v1/documents/copies").hasAnyAuthority("PERM_ALL", "PERM_DOCUMENT_SEND");
                    // Newly introduced API endpoints must be reviewed and granted an
                    // explicit method/path/permission rule. Never inherit access
                    // merely because the caller owns a legacy wildcard API key.
                    a.requestMatchers("/api/**").denyAll();
                    a.anyRequest().denyAll();
                })
                .addFilterBefore(new ApiKeyFilter(apiKeys, adminKey, allowLegacyKeys, credentials), UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
