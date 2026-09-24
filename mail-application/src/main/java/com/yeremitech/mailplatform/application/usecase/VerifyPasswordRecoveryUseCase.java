package com.yeremitech.mailplatform.application.usecase;

import com.yeremitech.mailplatform.application.model.*;
import com.yeremitech.mailplatform.application.port.PasswordRecoveryRepository;
import com.yeremitech.mailplatform.application.security.*;
import java.time.*;
import java.util.UUID;

public final class VerifyPasswordRecoveryUseCase {
    private final PasswordRecoveryRepository repository;
    private final TokenHasher hasher;
    private final SecureTokenGenerator tokens;
    private final Clock clock;

    public VerifyPasswordRecoveryUseCase(PasswordRecoveryRepository r, TokenHasher h, SecureTokenGenerator t, Clock c) {
        repository = r; hasher = h; tokens = t; clock = c;
    }

    public Result execute(String clientId, UUID id, String otp) {
        PasswordChallengeData ch = repository.findChallenge(clientId, id).orElseThrow(() -> new IllegalArgumentException("invalid challenge"));
        if (!clientId.equals(ch.clientId())) throw new IllegalArgumentException("invalid challenge");
        Instant now = clock.instant();
        if (!now.isBefore(ch.expiresAt())) throw new IllegalStateException("challenge expired");
        if (!hasher.matches(ch.otpHash(), ch.id().toString(), otp)) {
            repository.recordFailedAttempt(clientId, id, now);
            throw new IllegalArgumentException("invalid code");
        }

        UUID grantId = UUID.randomUUID();
        String token = tokens.grantToken();
        Instant exp = now.plus(Duration.ofMinutes(5));
        ResetGrantData grant = new ResetGrantData(
                grantId, ch.id(), ch.clientId(), ch.subjectReference(),
                hasher.hash(grantId.toString(), token), exp, now, null);

        repository.consumeChallengeAndSaveGrant(clientId, id, now, grant)
                .orElseThrow(() -> new IllegalStateException("challenge is no longer active"));
        return new Result(grantId, token, exp);
    }

    public record Result(UUID grantId, String resetGrant, Instant expiresAt) {}
}
