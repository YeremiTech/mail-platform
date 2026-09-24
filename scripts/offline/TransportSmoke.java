package com.yeremitech.mailplatform.api.service;

import java.net.InetAddress;
import java.net.URI;

/** JDK-only safety regression. No external DNS or network traffic. */
public final class TransportSmoke {
    public static void main(String[] args) throws Exception {
        InetAddress publicIp = InetAddress.getByName("8.8.8.8");
        InetAddress restricted = InetAddress.getByName("127.0.0.1");
        if (!WebhookAddressPolicy.choosePublicAddress(new InetAddress[]{publicIp}).equals(publicIp))
            throw new AssertionError("public IP selection failed");
        try {
            WebhookAddressPolicy.choosePublicAddress(new InetAddress[]{publicIp, restricted});
            throw new AssertionError("mixed DNS answers not rejected");
        } catch (IllegalArgumentException expected) { /* blocked */ }
        var transport = new PinnedWebhookTransport();
        try {
            transport.postPinned(URI.create("https://example.org/hook"), restricted, new byte[0], "id", "1", "hash");
            throw new AssertionError("private pinned IP accepted");
        } catch (IllegalArgumentException expected) { /* blocked without connecting */ }
        try {
            transport.post(URI.create("http://example.org/hook"), new byte[0], "id", "1", "hash");
            throw new AssertionError("plaintext webhook allowed");
        } catch (IllegalArgumentException expected) { /* blocked without resolving */ }
        System.out.println("PASS: HTTPS pinning refuses private IPs, mixed DNS answers and plaintext endpoints");
    }
}
