package com.yeremitech.mailplatform.application.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class TokenHasher {
    private final byte[] secret;
    public TokenHasher(String secret) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) throw new IllegalArgumentException("OTP HMAC secret must contain at least 32 bytes");
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }
    public String hash(String namespace, String raw) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((namespace + ':' + raw).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException("Unable to calculate token HMAC", e); }
    }
    public boolean matches(String expectedHex, String namespace, String raw) {
        return MessageDigest.isEqual(expectedHex.getBytes(StandardCharsets.US_ASCII), hash(namespace, raw).getBytes(StandardCharsets.US_ASCII));
    }
}
