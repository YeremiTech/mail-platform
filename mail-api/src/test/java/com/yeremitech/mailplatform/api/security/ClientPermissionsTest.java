package com.yeremitech.mailplatform.api.security;

import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ClientPermissionsTest {
    @Test void newClientsMustUseKnownExplicitPermissions() {
        assertEquals(Set.of("EMAIL_SEND", "TEMPLATE_READ"),
                ClientPermissions.normalize(Set.of(" email_send ", "template_read"), false));
        assertThrows(IllegalArgumentException.class, () -> ClientPermissions.normalize(Set.of("*"), false));
        assertThrows(IllegalArgumentException.class, () -> ClientPermissions.normalize(Set.of("SUPER_ADMIN"), false));
        assertThrows(IllegalArgumentException.class, () -> ClientPermissions.normalize(Set.of(), false));
        assertEquals(Set.of("METRICS_READ"), ClientPermissions.normalize(Set.of("metrics_read"), false));
    }
    @Test void legacyWildcardIsExclusive() {
        assertEquals(Set.of("*"), ClientPermissions.normalize(Set.of("*"), true));
        assertThrows(IllegalArgumentException.class,
                () -> ClientPermissions.normalize(Set.of("*", "EMAIL_SEND"), true));
    }
}
