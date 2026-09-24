package com.yeremitech.mailplatform.api.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** The published API metadata must describe the artifact, not a stale hardcoded release. */
class OpenApiConfigurationTest {
    @Test
    void documentsInjectedReleaseAndBothRequiredCredentialHeaders() {
        OpenAPI contract = new OpenApiConfiguration().mailPlatformOpenApi("0.10.4-SNAPSHOT");
        assertEquals("0.10.4-SNAPSHOT", contract.getInfo().getVersion());
        SecurityScheme id = contract.getComponents().getSecuritySchemes().get("clientId");
        SecurityScheme key = contract.getComponents().getSecuritySchemes().get("clientApiKey");
        assertEquals("X-Client-Id", id.getName());
        assertEquals("X-Internal-Api-Key", key.getName());
        assertEquals(SecurityScheme.Type.APIKEY, id.getType());
        assertEquals(SecurityScheme.Type.APIKEY, key.getType());
    }
}
