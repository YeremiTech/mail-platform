package com.yeremitech.mailplatform.api.service;

import java.net.InetAddress;
import java.net.UnknownHostException;

/** Conservative public-IP check. Network egress ACLs are still required against DNS rebinding. */
public final class WebhookAddressPolicy {
    private WebhookAddressPolicy() {}

    public static void verifyPublicAddress(String host) {
        resolvePublicAddress(host);
    }

    /** Resolve exactly once per request, reject mixed public/private results and return a pinned IP. */
    public static InetAddress resolvePublicAddress(String host) {
        try { return choosePublicAddress(InetAddress.getAllByName(host)); }
        catch (UnknownHostException ex) { throw new IllegalArgumentException("webhook DNS resolution failed", ex); }
    }

    static InetAddress choosePublicAddress(InetAddress[] resolved) {
        if (resolved == null || resolved.length == 0)
            throw new IllegalArgumentException("webhook host has no DNS addresses");
        for (InetAddress address : resolved) {
            if (!isPublic(address))
                throw new IllegalArgumentException("webhook host resolves to a restricted IP address");
        }
        return resolved[0];
    }

    /** Reject private, carrier NAT, documentation, benchmark, multicast and embedded IPv4 ranges. */
    public static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isSiteLocalAddress()
                || address.isLinkLocalAddress() || address.isMulticastAddress()) return false;
        byte[] bytes=address.getAddress();
        if (bytes.length==4) {
            int a=bytes[0]&255,b=bytes[1]&255,c=bytes[2]&255;
            if (a==0 || a==10 || a==127 || a>=224) return false;
            if (a==100 && b>=64 && b<=127) return false;
            if (a==169 && b==254) return false;
            if (a==172 && b>=16 && b<=31) return false;
            if (a==192 && (b==0 && (c==0 || c==2) || b==168)) return false;
            if (a==198 && (b==18 || b==19 || b==51 && c==100)) return false;
            if (a==203 && b==0 && c==113) return false;
            return true;
        }
        if (bytes.length!=16) return false;
        int a=bytes[0]&255,b=bytes[1]&255;
        // Limit callbacks to global IPv6 unicast (2000::/3) and exclude non-routed
        // documentation and IPv4-embedded transition ranges rather than guessing their route.
        if ((a & 0xe0)!=0x20) return false;
        if (a==0x20 && b==0x01) {
            if (bytes[2]==0x0d && (bytes[3]&255)==0xb8) return false; // 2001:db8::/32
            if (bytes[2]==0 && bytes[3]==0) return false; // Teredo 2001:0::/32
        }
        if (a==0x20 && b==0x02) return false; // 6to4 2002::/16
        return true;
    }
}
