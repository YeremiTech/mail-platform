package com.yeremitech.mailplatform.infrastructure.adapter;

import com.yeremitech.mailplatform.application.port.MailQueuePort;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

public final class RabbitMailQueueAdapter implements MailQueuePort {
    private final RabbitTemplate rabbit;

    public RabbitMailQueueAdapter(RabbitTemplate rabbit) {
        this.rabbit = rabbit;
    }

    @Override
    public void publish(String routingKey, String payload) {
        CorrelationData correlation = new CorrelationData(UUID.randomUUID().toString());
        rabbit.convertAndSend("mail.exchange", routingKey, payload, correlation);
        try {
            var confirmation = correlation.getFuture().get(5, TimeUnit.SECONDS);
            if (!confirmation.ack() || correlation.getReturned() != null) {
                throw new IllegalStateException("RabbitMQ did not accept the mail event");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("RabbitMQ confirmation interrupted", ex);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException ex) {
            throw new IllegalStateException("RabbitMQ confirmation failed or timed out", ex);
        }
    }
}
