package com.yeremitech.mailplatform.api.service;

import com.yeremitech.mailplatform.api.security.PersistentClientCredentials;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Low-cardinality operational metrics and incremental cleanup; never deletes mail referenced by messages. */
@Component
public class MailMaintenance {
    private static final Logger log = LoggerFactory.getLogger(MailMaintenance.class);
    private final NamedParameterJdbcTemplate jdbc;
    private final PersistentClientCredentials credentials;
    private final int retentionHours;
    private final AtomicLong queued = new AtomicLong();
    private final AtomicLong retrying = new AtomicLong();
    private final AtomicLong processing = new AtomicLong();
    private final AtomicLong deadOutbox = new AtomicLong();
    private final AtomicLong webhookPending = new AtomicLong();
    private final AtomicLong webhookDead = new AtomicLong();
    private final AtomicLong campaignStaged = new AtomicLong();
    private final AtomicLong campaignSuppressed = new AtomicLong();
    private final AtomicLong suppressedLastHour = new AtomicLong();
    private final AtomicLong unsubscribeTokensActive = new AtomicLong();
    private final AtomicLong unsubscribeRequestsLastHour = new AtomicLong();
    private final AtomicLong oldestQueuedSeconds = new AtomicLong();
    private final AtomicLong metricsRefreshHealthy = new AtomicLong();
    private final AtomicLong metricsLastSuccessEpoch = new AtomicLong();

    public MailMaintenance(NamedParameterJdbcTemplate jdbc, PersistentClientCredentials credentials,
            MeterRegistry registry, @Value("${app.attachments.orphan-retention-hours:48}") int retentionHours) {
        if (retentionHours < 24) throw new IllegalArgumentException("orphan-retention-hours must be at least 24");
        this.jdbc = jdbc; this.credentials=credentials; this.retentionHours=retentionHours;
        Gauge.builder("mail.messages.queued", queued, AtomicLong::doubleValue).register(registry);
        Gauge.builder("mail.messages.retrying", retrying, AtomicLong::doubleValue).register(registry);
        Gauge.builder("mail.messages.processing", processing, AtomicLong::doubleValue).register(registry);
        Gauge.builder("mail.outbox.dead", deadOutbox, AtomicLong::doubleValue).register(registry);
        Gauge.builder("mail.webhooks.pending", webhookPending, AtomicLong::doubleValue).register(registry);
        Gauge.builder("mail.webhooks.dead", webhookDead, AtomicLong::doubleValue).register(registry);
        Gauge.builder("mail.campaign.recipients.staged", campaignStaged, AtomicLong::doubleValue).register(registry);
        Gauge.builder("mail.campaign.recipients.suppressed", campaignSuppressed, AtomicLong::doubleValue).register(registry);
        Gauge.builder("mail.campaign.recipients.suppressed.last.hour", suppressedLastHour, AtomicLong::doubleValue).register(registry);
        Gauge.builder("mail.marketing.unsubscribe.tokens.active", unsubscribeTokensActive, AtomicLong::doubleValue).register(registry);
        Gauge.builder("mail.marketing.unsubscribe.requests.last.hour", unsubscribeRequestsLastHour, AtomicLong::doubleValue).register(registry);
        Gauge.builder("mail.messages.oldest.pending.seconds", oldestQueuedSeconds, AtomicLong::doubleValue).register(registry);
        Gauge.builder("mail.metrics.refresh.healthy", metricsRefreshHealthy, AtomicLong::doubleValue).register(registry);
        Gauge.builder("mail.metrics.last.success.epoch", metricsLastSuccessEpoch, AtomicLong::doubleValue).register(registry);
    }

    @Scheduled(fixedDelayString="${app.metrics.refresh-interval-ms:30000}")
    public void refreshMetrics() {
        try {
            java.util.Map<String, Long> counts = new java.util.HashMap<>();
            jdbc.query("""
                    select status, count(*) as total from mail_message
                    where status in ('QUEUED','RETRYING','PROCESSING') group by status
                    """, (rs, row) -> {
                long n=rs.getLong("total");
                counts.put(rs.getString("status"), n);
                return n;
            });
            Long dead = jdbc.queryForObject("select count(*) from mail_outbox_event where status='DEAD' and coalesce(last_error,'') <> 'Cancelled by client'",
                    new MapSqlParameterSource(), Long.class);
            Long pendingHooks = jdbc.queryForObject("select count(*) from mail_webhook_delivery where status in ('PENDING','RETRYING','DELIVERING')",
                    new MapSqlParameterSource(), Long.class);
            Long deadHooks = jdbc.queryForObject("select count(*) from mail_webhook_delivery where status='DEAD'",
                    new MapSqlParameterSource(), Long.class);
            Long staged = jdbc.queryForObject("select count(*) from mail_campaign_recipient where state='PENDING'",
                    new MapSqlParameterSource(), Long.class);
            Long suppressed = jdbc.queryForObject("select count(*) from mail_campaign_recipient where state='SUPPRESSED'",
                    new MapSqlParameterSource(),Long.class);
            Long suppressedRecent=jdbc.queryForObject("""
                    select count(*) from mail_campaign_recipient
                    where state='SUPPRESSED' and suppressed_at>=current_timestamp - interval '1 hour'
                    """,new MapSqlParameterSource(),Long.class);
            Long activeLinks=jdbc.queryForObject("""
                    select count(*) from mail_unsubscribe_token
                    where expires_at>current_timestamp and consumed_at is null
                    """,new MapSqlParameterSource(),Long.class);
            Long consumedRecent=jdbc.queryForObject("""
                    select count(*) from mail_unsubscribe_token
                    where consumed_at>=current_timestamp - interval '1 hour'
                    """,new MapSqlParameterSource(),Long.class);
            Long oldest = jdbc.queryForObject("""
                    select coalesce(extract(epoch from (current_timestamp - min(created_at)))::bigint, 0)
                    from mail_message where status in ('QUEUED','RETRYING')
                    and (scheduled_at is null or scheduled_at <= current_timestamp)
                    """, new MapSqlParameterSource(), Long.class);
            queued.set(counts.getOrDefault("QUEUED", 0L));
            retrying.set(counts.getOrDefault("RETRYING", 0L));
            processing.set(counts.getOrDefault("PROCESSING", 0L));
            deadOutbox.set(dead == null ? 0 : dead);
            webhookPending.set(pendingHooks == null ? 0 : pendingHooks);
            webhookDead.set(deadHooks == null ? 0 : deadHooks);
            campaignStaged.set(staged == null ? 0 : staged);
            campaignSuppressed.set(suppressed == null ? 0 : suppressed);
            suppressedLastHour.set(suppressedRecent == null ? 0 : suppressedRecent);
            unsubscribeTokensActive.set(activeLinks == null ? 0 : activeLinks);
            unsubscribeRequestsLastHour.set(consumedRecent == null ? 0 : consumedRecent);
            oldestQueuedSeconds.set(oldest == null ? 0 : oldest);
            metricsRefreshHealthy.set(1);
            metricsLastSuccessEpoch.set(java.time.Instant.now().getEpochSecond());
        } catch (RuntimeException ex) {
            metricsRefreshHealthy.set(0); // do not publish stale queue values as fresh data
            log.warn("Unable to refresh mail metrics", ex);
        }
    }

    @Scheduled(fixedDelayString="${app.maintenance.cleanup-interval-ms:3600000}")
    public void cleanup() {
        try {
            if (credentials.configured()) credentials.cleanupExpiredWindows();
            jdbc.update("delete from mail_api_daily_usage where usage_day < current_date - 90",
                    new MapSqlParameterSource());
            // Bound each pass to avoid long-running transactions and vacuum pressure.
            jdbc.update("""
                    delete from mail_unsubscribe_token where token_digest in (
                        select token_digest from mail_unsubscribe_token
                        where expires_at<current_timestamp - interval '30 days'
                           or consumed_at<current_timestamp - interval '30 days'
                        order by expires_at limit 200 for update skip locked
                    )
                    """, new MapSqlParameterSource());
            int deleted = jdbc.update("""
                    delete from mail_attachment where id in (
                        select a.id from mail_attachment a
                        where a.created_at < current_timestamp - (:hours * interval '1 hour')
                          and a.legal_hold = false
                          and (a.retention_until is null or a.retention_until <= current_timestamp)
                          and not exists (select 1 from mail_message_attachment r where r.attachment_id=a.id)
                          and not exists (select 1 from mail_campaign_attachment ca where ca.attachment_id=a.id)
                        order by a.created_at
                        limit 200
                        for update skip locked
                    )
                    """, Map.of("hours", retentionHours));
            if (deleted > 0) log.info("Removed {} unreferenced attachments", deleted);
        } catch (RuntimeException ex) { log.warn("Unable to run mail maintenance", ex); }
    }
}
