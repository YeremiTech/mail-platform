package com.yeremitech.mailplatform.application.port;

public interface MailQueuePort {
    void publish(String routingKey, String payload);
}
