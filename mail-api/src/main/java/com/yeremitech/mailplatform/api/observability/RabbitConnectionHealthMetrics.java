package com.yeremitech.mailplatform.api.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** One low-cardinality RabbitMQ availability metric per API instance. */
@Component
public final class RabbitConnectionHealthMetrics {
    private final ConnectionFactory factory;
    private final AtomicInteger available = new AtomicInteger();

    public RabbitConnectionHealthMetrics(ConnectionFactory factory, MeterRegistry registry) {
        this.factory = factory;
        Gauge.builder("mail.rabbitmq.available", available, AtomicInteger::doubleValue)
                .description("1 when the shared RabbitMQ connection is open, 0 otherwise")
                .register(registry);
    }

    @Scheduled(fixedDelayString = "${app.metrics.dependency-poll-interval-ms:30000}")
    public void refresh() {
        Connection connection = null;
        try {
            connection = factory.createConnection();
            available.set(connection.isOpen() ? 1 : 0);
        } catch (RuntimeException unavailable) {
            available.set(0);
        } finally {
            if (connection != null) {
                try { connection.close(); }
                catch (RuntimeException unavailable) { available.set(0); }
            }
        }
    }
}
