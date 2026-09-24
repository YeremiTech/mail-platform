package com.yeremitech.mailplatform.api.service;

import com.yeremitech.mailplatform.domain.EmailAddress;
import com.yeremitech.mailplatform.domain.UnsubscribeToken;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Tokens are issued only by authenticated campaign workers; public POST atomically consumes one. */
@Service
public class UnsubscribeLinkService {
    private final NamedParameterJdbcTemplate jdbc;
    private final SuppressionService suppressions;
    private final Clock clock;
    private final String origin;
    private final int ttlDays;

    public UnsubscribeLinkService(NamedParameterJdbcTemplate jdbc, SuppressionService suppressions,
            Clock clock, @Value("${app.marketing.public-base-url:}") String origin,
            @Value("${app.marketing.unsubscribe-token-days:90}") int ttlDays) {
        if (ttlDays < 1 || ttlDays > 365) throw new IllegalArgumentException("unsubscribe-token-days must be 1..365");
        this.jdbc=jdbc;this.suppressions=suppressions;this.clock=clock;
        this.origin=origin==null ? "" : origin.trim();this.ttlDays=ttlDays;
        // Avoid coupling basic transactional mail startup to optional marketing configuration.
        if (!this.origin.isEmpty()) UnsubscribeToken.validatePublicBaseUrl(this.origin);
    }

    public void requireMarketingConfiguration() { UnsubscribeToken.validatePublicBaseUrl(origin); }

    /** Called within the same transaction that queues the associated campaign email. */
    @Transactional
    public String issue(String clientId,String email) {
        String normalized=new EmailAddress(email).value();
        String token=UnsubscribeToken.issue();
        Instant expiry=clock.instant().plus(Duration.ofDays(ttlDays));
        int inserted=jdbc.update("""
                insert into mail_unsubscribe_token(token_digest,client_id,email,expires_at)
                values (:digest,:client,:email,:expiry)
                """,Map.of("digest",UnsubscribeToken.digest(token),"client",clientId,
                            "email",normalized,"expiry",Timestamp.from(expiry)));
        if (inserted!=1) throw new IllegalStateException("unable to issue unsubscribe token");
        return UnsubscribeToken.validatePublicBaseUrl(origin)
                + "/api/v1/public/unsubscribe?token=" + token;
    }

    /** Generic response for invalid, expired, previously consumed and valid links. */
    @Transactional
    public void consume(String token) {
        if (!UnsubscribeToken.wellFormed(token)) return;
        List<Recipient> recipient=jdbc.query("""
                update mail_unsubscribe_token set consumed_at=:now
                where token_digest=:digest and consumed_at is null and expires_at>:now
                returning client_id,email
                """,Map.of("now",Timestamp.from(clock.instant()),"digest",UnsubscribeToken.digest(token)),
                (rs,row)->new Recipient(rs.getString(1),rs.getString(2)));
        if (!recipient.isEmpty()) {
            Recipient target=recipient.getFirst();
            suppressions.suppress(target.clientId(),target.email(),SuppressionService.Reason.UNSUBSCRIBED);
        }
    }
    private record Recipient(String clientId,String email) {}
}
