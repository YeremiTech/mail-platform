package com.yeremitech.mailplatform.domain;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** Opaque, single-purpose 256-bit bearer token; store only its SHA-256 digest. */
public final class UnsubscribeToken {
    private static final SecureRandom RNG = new SecureRandom();
    private UnsubscribeToken() {}

    public static String issue() {
        byte[] bytes = new byte[32];
        RNG.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static boolean wellFormed(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) return false;
        try {
            byte[] decoded=Base64.getUrlDecoder().decode(token);
            return decoded.length==32 && token.equals(Base64.getUrlEncoder().withoutPadding().encodeToString(decoded));
        } catch (IllegalArgumentException ex) { return false; }
    }

    public static String digest(String token) {
        if (!wellFormed(token)) throw new IllegalArgumentException("invalid unsubscribe token");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    /** Only an administrator-supplied HTTPS origin (or local development loopback) is accepted. */
    public static String validatePublicBaseUrl(String url) {
        if (url == null || url.isBlank()) throw new IllegalStateException(
                "MARKETING_PUBLIC_BASE_URL must be configured to send marketing campaigns");
        URI uri;
        try { uri = URI.create(url.trim()); }
        catch (IllegalArgumentException ex) { throw new IllegalArgumentException("invalid marketing public base URL", ex); }
        if (!uri.isAbsolute() || uri.getHost() == null || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null
                || uri.getHost().contains("%") || (uri.getRawPath() != null && !uri.getRawPath().isEmpty()
                && !uri.getRawPath().equals("/")))
            throw new IllegalArgumentException("marketing public base URL must be a bare origin");
        boolean https = "https".equalsIgnoreCase(uri.getScheme());
        boolean dev = "http".equalsIgnoreCase(uri.getScheme()) && ("localhost".equalsIgnoreCase(uri.getHost())
                || "127.0.0.1".equals(uri.getHost()) || "[::1]".equals(uri.getHost()));
        if (!https && !dev) throw new IllegalArgumentException("marketing URL requires HTTPS (except localhost)");
        if (uri.getPort() == 0 || uri.getPort() > 65535 || uri.getPort() < -1)
            throw new IllegalArgumentException("invalid marketing URL port");
        String origin = uri.toASCIIString();
        return origin.endsWith("/") ? origin.substring(0,origin.length()-1) : origin;
    }
}
