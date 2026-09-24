package com.yeremitech.mailplatform.api;

import com.yeremitech.mailplatform.api.security.PersistentClientCredentials;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties={"app.worker.enabled=false","spring.rabbitmq.listener.simple.auto-startup=false"})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named="MAIL_PLATFORM_SMOKE_DB_URL",matches=".+")
class ClientPermissionIntegrationTest {
    @Autowired PersistentClientCredentials credentials;
    @Autowired MockMvc mvc;

    @Test void leastPrivilegeAndImmediateRevocation() throws Exception {
        Assumptions.assumeTrue(credentials.configured());
        String client = "acl-" + UUID.randomUUID();
        var key = credentials.createClient(client, "ACL test", 120, Duration.ofDays(1),
                Set.of("TEMPLATE_READ"));
        mvc.perform(get("/api/v1/templates")
                .header("X-Client-Id",client).header("X-Internal-Api-Key",key.apiKey()))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/templates/forbidden").contentType(MediaType.APPLICATION_JSON)
                .content("{\"html\":\"<p>x</p>\",\"text\":\"x\",\"requiredVariables\":[]}")
                .header("X-Client-Id",client).header("X-Internal-Api-Key",key.apiKey()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/emails").contentType(MediaType.APPLICATION_JSON).content("{}")
                .header("X-Client-Id",client).header("X-Internal-Api-Key",key.apiKey()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/clients")
                .header("X-Client-Id",client).header("X-Internal-Api-Key",key.apiKey()))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/admin/attachments/"+UUID.randomUUID()+"/retention")
                .contentType(MediaType.APPLICATION_JSON).content("{}")
                .header("X-Client-Id",client).header("X-Internal-Api-Key",key.apiKey()))
                .andExpect(status().isForbidden());
        credentials.setPermissions(client, Set.of("EMAIL_SEND"));
        mvc.perform(get("/api/v1/templates")
                .header("X-Client-Id",client).header("X-Internal-Api-Key",key.apiKey()))
                .andExpect(status().isForbidden());
        credentials.revoke(client,key.credentialId());
        mvc.perform(get("/api/v1/templates")
                .header("X-Client-Id",client).header("X-Internal-Api-Key",key.apiKey()))
                .andExpect(status().isUnauthorized());
    }

    @Test void administratorsCannotCreateImplicitOrUnknownPermissions() throws Exception {
        String admin=System.getenv("ADMIN_API_KEY");
        Assumptions.assumeTrue(admin!=null && !admin.isBlank());
        String id="acl-"+UUID.randomUUID();
        for (String suffix : new String[]{"", ",\"permissions\":[\"*\"]",
                ",\"permissions\":[\"SUPER_ADMIN\"]"}) {
            String json="{\"clientId\":\""+id+"\",\"displayName\":\"ACL test\",\"requestsPerMinute\":120"+suffix+"}";
            mvc.perform(post("/api/v1/admin/clients").contentType(MediaType.APPLICATION_JSON).content(json)
                    .header("X-Client-Id","admin").header("X-Internal-Api-Key",admin))
                    .andExpect(status().isBadRequest());
        }
    }
    @Test void operationalMetricsRequireDedicatedPermissionEvenForWildcardClient() throws Exception {
        Assumptions.assumeTrue(credentials.configured());
        String client = "metrics-" + UUID.randomUUID();
        var key = credentials.createClient(client, "Metrics guard test", 120, Duration.ofDays(1), Set.of("EMAIL_READ"));
        mvc.perform(get("/actuator/prometheus")
                .header("X-Client-Id", client).header("X-Internal-Api-Key", key.apiKey()))
                .andExpect(status().isForbidden());
        // Existing wildcard permissions remain valid for API operations, never operational monitoring.
        credentials.setPermissions(client, Set.of("*"));
        mvc.perform(get("/actuator/prometheus")
                .header("X-Client-Id", client).header("X-Internal-Api-Key", key.apiKey()))
                .andExpect(status().isForbidden());
        credentials.setPermissions(client, Set.of("METRICS_READ"));
        mvc.perform(get("/actuator/prometheus")
                .header("X-Client-Id", client).header("X-Internal-Api-Key", key.apiKey()))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/templates")
                .header("X-Client-Id", client).header("X-Internal-Api-Key", key.apiKey()))
                .andExpect(status().isForbidden());
    }

    @Test void unreviewedRoutesDenyEvenWildcardAndAdminCredentials() throws Exception {
        Assumptions.assumeTrue(credentials.configured());
        String client = "fallback-" + UUID.randomUUID();
        var key = credentials.createClient(client, "Fail-closed route test", 120,
                Duration.ofDays(1), Set.of("EMAIL_READ"));
        String path = "/api/v1/security-future-endpoint-not-reviewed";
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).header("X-Client-Id", client)
                .header("X-Internal-Api-Key", key.apiKey()))
                .andExpect(status().isForbidden());
        credentials.setPermissions(client, Set.of("*"));
        mvc.perform(get(path).header("X-Client-Id", client)
                .header("X-Internal-Api-Key", key.apiKey()))
                .andExpect(status().isForbidden());
        String admin = System.getenv("ADMIN_API_KEY");
        if (admin != null && !admin.isBlank()) {
            mvc.perform(get(path).header("X-Client-Id", "admin")
                    .header("X-Internal-Api-Key", admin))
                    .andExpect(status().isForbidden());
        }
    }
}
