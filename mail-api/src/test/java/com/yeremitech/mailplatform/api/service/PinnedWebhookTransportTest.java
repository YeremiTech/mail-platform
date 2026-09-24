package com.yeremitech.mailplatform.api.service;

import java.net.InetAddress;
import java.net.URI;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PinnedWebhookTransportTest {
    @Test void disallowsPrivatePinnedAddressesBeforeOpeningSocket() throws Exception {
        var transport = new PinnedWebhookTransport();
        for (String blocked : new String[]{"127.0.0.1", "10.1.0.1", "169.254.169.254"}) {
            assertThrows(IllegalArgumentException.class, () -> transport.postPinned(
                    URI.create("https://example.org/events"), InetAddress.getByName(blocked),
                    "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8), "id", "1", "hash"));
        }
    }

    @Test void rejectsPlainHttpWithoutNetworkAccess() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new PinnedWebhookTransport().post(
                URI.create("http://example.org/events"), new byte[]{1}, "id", "1", "hash"));
    }
}
