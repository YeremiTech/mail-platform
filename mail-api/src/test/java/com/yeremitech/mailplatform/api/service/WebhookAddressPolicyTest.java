package com.yeremitech.mailplatform.api.service;

import java.net.InetAddress;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WebhookAddressPolicyTest {
    @Test void rejectsPrivatePubliclyReservedAndTransitionAddresses() throws Exception {
        for (String address : new String[]{"127.0.0.1","10.1.1.1","172.16.0.1","192.168.1.5",
                "169.254.169.254","100.64.0.1","198.18.0.1","192.0.2.1",
                "198.51.100.1","203.0.113.1","224.0.0.1","0.0.0.0",
                "::1","fc00::1","fe80::1","2001:db8::10","2002:7f00:1::1"}) {
            assertFalse(WebhookAddressPolicy.isPublic(InetAddress.getByName(address)),address);
        }
    }
    @Test void permitsRoutableTestExamples() throws Exception {
        assertTrue(WebhookAddressPolicy.isPublic(InetAddress.getByName("8.8.8.8")));
        assertTrue(WebhookAddressPolicy.isPublic(InetAddress.getByName("2606:4700:4700::1111")));
    }
    @Test void rejectsMixedPublicAndPrivateDnsAnswersBeforeOpeningAnySocket() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> WebhookAddressPolicy.choosePublicAddress(new InetAddress[]{
                        InetAddress.getByName("8.8.8.8"), InetAddress.getByName("127.0.0.1")}));
        assertEquals(InetAddress.getByName("8.8.8.8"),
                WebhookAddressPolicy.choosePublicAddress(new InetAddress[]{InetAddress.getByName("8.8.8.8")}));
    }

}
