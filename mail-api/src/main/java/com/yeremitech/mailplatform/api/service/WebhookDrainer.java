package com.yeremitech.mailplatform.api.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix="app.worker",name="enabled",havingValue="true",matchIfMissing=true)
public class WebhookDrainer {
    private static final Logger log=LoggerFactory.getLogger(WebhookDrainer.class);
    private final SignedWebhookService hooks;
    public WebhookDrainer(SignedWebhookService hooks){this.hooks=hooks;}
    @Scheduled(fixedDelayString="${app.webhooks.poll-interval-ms:10000}")
    public void deliver(){
        try {
            for(var event:hooks.claim()) hooks.dispatchClaimed(event);
        } catch(RuntimeException ex){log.error("Webhook delivery cycle failed",ex);}
    }
}
