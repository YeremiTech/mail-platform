package com.yeremitech.mailplatform.api.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RabbitConnectionHealthMetricsTest {
    @Test void exposesBrokerAvailabilityWithoutClientOrMessageLabels() {
        ConnectionFactory factory = mock(ConnectionFactory.class);
        Connection connection = mock(Connection.class);
        when(factory.createConnection()).thenReturn(connection);
        when(connection.isOpen()).thenReturn(true);
        try (var registry = new SimpleMeterRegistry()) {
            var monitor = new RabbitConnectionHealthMetrics(factory, registry);
            monitor.refresh();
            var gauge = registry.get("mail.rabbitmq.available").gauge();
            assertEquals(1.0, gauge.value());
            assertTrue(gauge.getId().getTags().isEmpty());
            verify(connection).close();
            when(connection.isOpen()).thenReturn(false);
            monitor.refresh();
            assertEquals(0.0, gauge.value());
        }
    }

    @Test void reportsUnavailableInsteadOfThrowingWhenBrokerIsDown() {
        ConnectionFactory factory = mock(ConnectionFactory.class);
        when(factory.createConnection()).thenThrow(new IllegalStateException("broker offline"));
        try (var registry = new SimpleMeterRegistry()) {
            var monitor = new RabbitConnectionHealthMetrics(factory, registry);
            assertDoesNotThrow(monitor::refresh);
            assertEquals(0.0, registry.get("mail.rabbitmq.available").gauge().value());
        }
    }
}
