package com.yeremitech.mailplatform.worker;

import com.yeremitech.mailplatform.application.port.MailRetryPort;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.scheduling.annotation.Scheduled;

public final class StaleProcessingRecovery {
    private final MailRetryPort retry;
    private final Clock clock;
    private final Duration staleAfter;
    private final int batchSize;

    public StaleProcessingRecovery(
            MailRetryPort retry,
            Clock clock,
            Duration staleAfter,
            int batchSize) {
        this.retry = retry;
        this.clock = clock;
        this.staleAfter = staleAfter;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${app.delivery.stale-recovery-interval-ms:60000}")
    public void recover() {
        Instant now = clock.instant();
        retry.recoverStaleProcessing(now.minus(staleAfter), now, batchSize);
    }
}
