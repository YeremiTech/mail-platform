package com.yeremitech.mailplatform.application.port;

import java.time.Duration;
import java.time.Instant;

public interface PasswordRecoveryThrottlePort {
    void acquire(
            String clientId,
            String subjectReference,
            Instant now,
            Duration window,
            int maxRequests,
            Duration cooldown);
}
