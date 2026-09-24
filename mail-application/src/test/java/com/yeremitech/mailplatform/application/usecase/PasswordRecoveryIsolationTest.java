package com.yeremitech.mailplatform.application.usecase;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.yeremitech.mailplatform.application.model.PasswordChallengeData;
import com.yeremitech.mailplatform.application.model.ResetGrantData;
import com.yeremitech.mailplatform.application.port.PasswordRecoveryRepository;
import com.yeremitech.mailplatform.application.security.SecureTokenGenerator;
import com.yeremitech.mailplatform.application.security.TokenHasher;
import com.yeremitech.mailplatform.domain.ChallengeStatus;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PasswordRecoveryIsolationTest {
    private final Instant now = Instant.parse("2026-09-23T12:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    private final TokenHasher hasher = new TokenHasher("a-long-test-secret-with-at-least-32-bytes");

    @Test
    void cannotVerifyChallengeOwnedByAnotherClient() {
        UUID id = UUID.randomUUID();
        PasswordChallengeData challenge = new PasswordChallengeData(id, "billing", "user-1", "user@example.com",
                hasher.hash(id.toString(), "123456"), ChallengeStatus.ACTIVE, 0, 5, now.plusSeconds(600), now, null);
        var useCase = new VerifyPasswordRecoveryUseCase(repository(challenge, null), hasher, new SecureTokenGenerator(), clock);
        assertThrows(IllegalArgumentException.class, () -> useCase.execute("inventory", id, "123456"));
    }

    @Test
    void cannotConsumeGrantOwnedByAnotherClient() {
        UUID id = UUID.randomUUID();
        ResetGrantData grant = new ResetGrantData(id, UUID.randomUUID(), "billing", "user-1",
                hasher.hash(id.toString(), "token"), now.plusSeconds(300), now, null);
        var useCase = new ConsumeResetGrantUseCase(repository(null, grant), hasher, clock);
        assertThrows(IllegalArgumentException.class, () -> useCase.execute("inventory", id, "token"));
    }

    private PasswordRecoveryRepository repository(PasswordChallengeData challenge, ResetGrantData grant) {
        return (PasswordRecoveryRepository) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{PasswordRecoveryRepository.class}, (proxy, method, args) -> {
                    if (method.getName().equals("findChallenge")) return Optional.ofNullable(challenge);
                    if (method.getName().equals("findGrant")) return Optional.ofNullable(grant);
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
