package com.yeremitech.mailplatform.application.security;

import java.security.SecureRandom;
import java.util.Base64;

public final class SecureTokenGenerator {
    private final SecureRandom random = new SecureRandom();
    public String sixDigitOtp() { return "%06d".formatted(random.nextInt(1_000_000)); }
    public String grantToken() { byte[] bytes = new byte[32]; random.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
}
