package com.yeremitech.mailplatform.api.security;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

class ClientTemplatePolicyTest {
    @Test
    void onlyConfiguredClientCanUseGeneralTemplate() {
        ClientTemplatePolicy policy = new ClientTemplatePolicy("inventory=billing/invoice");
        assertDoesNotThrow(() -> policy.assertAllowed("inventory", "billing/invoice"));
        assertThrows(AccessDeniedException.class, () -> policy.assertAllowed("billing", "billing/invoice"));
        assertThrows(AccessDeniedException.class, () -> policy.assertAllowed("inventory", "security/password-reset"));
        assertThrows(AccessDeniedException.class, () -> policy.assertAllowed("inventory", "billing/document-copy"));
    }
}
