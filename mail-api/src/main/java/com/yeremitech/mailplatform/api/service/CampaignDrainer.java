package com.yeremitech.mailplatform.api.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix="app.worker",name="enabled",havingValue="true",matchIfMissing=true)
public class CampaignDrainer {
    private static final Logger log=LoggerFactory.getLogger(CampaignDrainer.class);
    private final CampaignService campaigns;
    public CampaignDrainer(CampaignService campaigns){this.campaigns=campaigns;}
    @Scheduled(fixedDelayString="${app.campaign.poll-interval-ms:1000}")
    public void drain(){
        try { campaigns.drainOneChunk(); }
        catch (CampaignService.CampaignQuotaExceededException ex) {
            campaigns.deferUntilNextUtcDay(ex.batchId());
            log.warn("Campaign {} deferred until its client daily quota resets",ex.batchId());
        }
        catch (RuntimeException ex) { log.error("Unable to drain staged campaign",ex); }
    }
}
