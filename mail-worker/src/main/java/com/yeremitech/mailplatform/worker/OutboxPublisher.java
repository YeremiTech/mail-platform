package com.yeremitech.mailplatform.worker;

import com.yeremitech.mailplatform.application.port.MailQueuePort;
import com.yeremitech.mailplatform.application.port.OutboxRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.scheduling.annotation.Scheduled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class OutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private final OutboxRepository outbox;
    private final MailQueuePort queue;
    private final Clock clock;
    private final int batchSize;
    private final int maxAttempts;
    private final Duration lease;

    public OutboxPublisher(
            OutboxRepository outbox,
            MailQueuePort queue,
            Clock clock,
            int batchSize,
            int maxAttempts,
            Duration lease) {
        this.outbox = outbox;
        this.queue = queue;
        this.clock = clock;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
        this.lease = lease;
        if (batchSize < 1 || maxAttempts < 1 || lease.compareTo(Duration.ofSeconds(batchSize * 5L + 10L)) < 0) {
            throw new IllegalArgumentException("outbox lease must exceed the worst-case broker confirmation time for the batch");
        }
    }

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:1000}")
    public void publishReadyEvents() {
        Instant now = clock.instant();
        var events = outbox.claimReady(now, now.plus(lease), batchSize);
        for (var event : events) {
            try {
                queue.publish(event.routingKey(), event.payload());
                if (!outbox.markPublished(event.id(), event.lockedUntil(), clock.instant())) {
                    log.warn("Outbox lease was lost after publishing event {}", event.id());
                }
            } catch (RuntimeException ex) {
                int attempts = event.attempts() + 1;
                boolean terminal = attempts >= maxAttempts;
                Instant failedAt = clock.instant();
                Instant next = terminal
                        ? failedAt
                        : failedAt.plus(backoff(attempts));
                outbox.markFailed(
                        event.id(), event.lockedUntil(), attempts, next, terminal,
                        rootMessage(ex), failedAt);
            }
        }
    }

    private static Duration backoff(int attempt) {
        long seconds = Math.min(300L, 1L << Math.min(attempt, 8));
        return Duration.ofSeconds(seconds);
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return current.getClass().getName() + (message == null ? "" : ": " + message);
    }
}
