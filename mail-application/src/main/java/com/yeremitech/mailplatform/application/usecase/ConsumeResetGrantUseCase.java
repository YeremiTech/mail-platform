package com.yeremitech.mailplatform.application.usecase;

import com.yeremitech.mailplatform.application.model.ResetGrantData;
import com.yeremitech.mailplatform.application.port.PasswordRecoveryRepository;
import com.yeremitech.mailplatform.application.security.TokenHasher;
import java.time.Clock;
import java.util.UUID;

public final class ConsumeResetGrantUseCase {
    private final PasswordRecoveryRepository repository;
    private final TokenHasher hasher;
    private final Clock clock;

    public ConsumeResetGrantUseCase(PasswordRecoveryRepository r, TokenHasher h, Clock c) { repository = r; hasher = h; clock = c; }

    public Result execute(String clientId, UUID grantId, String rawToken) {
        ResetGrantData g = repository.findGrant(clientId, grantId).orElseThrow(() -> new IllegalArgumentException("invalid reset grant"));
        if (!clientId.equals(g.clientId())) throw new IllegalArgumentException("invalid reset grant");
        var now = clock.instant();
        if (g.consumed() || !now.isBefore(g.expiresAt()) || !hasher.matches(g.tokenHash(), g.id().toString(), rawToken)) {
            throw new IllegalArgumentException("invalid reset grant");
        }
        ResetGrantData consumed = repository.consumeGrant(clientId, grantId, now)
                .orElseThrow(() -> new IllegalArgumentException("invalid reset grant"));
        return new Result(consumed.clientId(), consumed.subjectReference());
    }

    public record Result(String clientId, String subjectReference) {}
}
