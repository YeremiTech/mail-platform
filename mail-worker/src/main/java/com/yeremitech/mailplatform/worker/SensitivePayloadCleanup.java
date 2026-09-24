package com.yeremitech.mailplatform.worker;

import com.yeremitech.mailplatform.application.port.SensitivePayloadPort;
import java.time.Clock;
import org.springframework.scheduling.annotation.Scheduled;

public final class SensitivePayloadCleanup {
    private final SensitivePayloadPort payloads;
    private final Clock clock;
    private final int batchSize;

    public SensitivePayloadCleanup(SensitivePayloadPort payloads, Clock clock, int batchSize) {
        this.payloads = payloads; this.clock = clock; this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${app.sensitive-payload.cleanup-interval-ms:60000}")
    public void purgeExpired() { payloads.purgeExpired(clock.instant(), batchSize); }
}
