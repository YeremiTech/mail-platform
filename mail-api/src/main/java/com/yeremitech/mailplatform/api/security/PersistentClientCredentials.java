package com.yeremitech.mailplatform.api.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** HMAC is safe here only for uniformly random 256-bit-or-more API keys. Never accept user passwords as keys. */
public class PersistentClientCredentials {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final NamedParameterJdbcTemplate jdbc;
    private final byte[] pepper;
    private final boolean configured;

    public PersistentClientCredentials(NamedParameterJdbcTemplate jdbc, String pepper) {
        if (pepper != null && !pepper.isBlank() && pepper.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("API_KEY_PEPPER must be at least 32 bytes");
        }
        this.jdbc = jdbc;
        this.configured = pepper != null && !pepper.isBlank();
        this.pepper = configured ? pepper.getBytes(StandardCharsets.UTF_8).clone() : new byte[0];
    }

    public boolean configured() { return configured; }

    private void requireConfigured() {
        if (!configured) throw new IllegalStateException("API_KEY_PEPPER must be configured before provisioning clients");
    }

    @Transactional
    public IssuedCredential createClient(String clientId, String name, int rpm, Duration ttl, Set<String> permissions) {
        requireConfigured();
        Set<String> requested = ClientPermissions.normalize(permissions, false);
        checkClientId(clientId);
        if (name == null || name.isBlank() || name.length() > 200 || rpm < 1 || rpm > 10000) {
            throw new IllegalArgumentException("invalid client name or request quota");
        }
        jdbc.update("""
                insert into mail_api_client(client_id, display_name, requests_per_minute, permissions)
                values (:id, :name, :rpm, :permissions)
                """, Map.of("id", clientId, "name", name, "rpm", rpm,
                        "permissions", String.join(",", requested.stream().sorted().toList())));
        return issueKey(clientId, ttl);
    }

    @Transactional
    public IssuedCredential issueKey(String clientId, Duration ttl) {
        requireConfigured();
        checkClientId(clientId);
        if (ttl != null && (ttl.compareTo(Duration.ofMinutes(1)) < 0 || ttl.compareTo(Duration.ofDays(730)) > 0)) {
            throw new IllegalArgumentException("credential TTL must be between one minute and two years");
        }
        if (jdbc.queryForObject("select count(*) from mail_api_client where client_id=:id and enabled=true",
                Map.of("id", clientId), Integer.class) != 1) {
            throw new IllegalArgumentException("client not found or disabled");
        }
        byte[] token = new byte[32];
        RANDOM.nextBytes(token);
        String raw = "mp_" + HexFormat.of().formatHex(token);
        UUID keyId = UUID.randomUUID();
        Instant expiry = ttl == null ? null : Instant.now().plus(ttl);
        jdbc.update("""
                insert into mail_api_credential(id, client_id, secret_mac, expires_at)
                values (:id, :client, :mac, :expiry)
                """, new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                .addValue("id", keyId).addValue("client", clientId)
                .addValue("mac", mac(raw)).addValue("expiry", expiry == null ? null : java.sql.Timestamp.from(expiry)));
        return new IssuedCredential(keyId, clientId, raw, expiry);
    }

    /** Does not leak whether a client exists, has expired credentials or was disabled. */
    public boolean authenticate(String clientId, String raw) {
        if (!configured || clientId == null || !clientId.matches("[A-Za-z0-9._-]{1,120}")
                || raw == null || !raw.matches("mp_[0-9a-f]{64}")) return false;
        String providedMac = mac(raw);
        List<ActiveCredential> active = jdbc.query("""
                select k.id,k.secret_mac from mail_api_credential k
                join mail_api_client c on c.client_id=k.client_id
                where k.client_id=:id and c.enabled=true and k.revoked_at is null
                  and (k.expires_at is null or k.expires_at > current_timestamp)
                """, Map.of("id", clientId), (rs, row) -> new ActiveCredential(rs.getObject(1, UUID.class), rs.getString(2)));
        UUID matchedId = null;
        for (ActiveCredential candidate : active) {
            boolean equal = MessageDigest.isEqual(providedMac.getBytes(StandardCharsets.US_ASCII),
                    candidate.mac().getBytes(StandardCharsets.US_ASCII));
            if (equal) matchedId = candidate.id();
        }
        if (matchedId != null) {
            jdbc.update("update mail_api_credential set last_used_at=current_timestamp where id=:id", Map.of("id", matchedId));
            return true;
        }
        return false;
    }

    /** Called only after authentication. Atomic across API instances. */
    public boolean consumeRequestQuota(String clientId) {
        List<Integer> limits = jdbc.query("select requests_per_minute from mail_api_client where client_id=:id and enabled=true",
                Map.of("id", clientId), (rs, row) -> rs.getInt(1));
        if (limits.isEmpty()) return false;
        Integer count = jdbc.queryForObject("""
                insert into mail_api_request_window(client_id, window_started_at, request_count)
                values (:id, date_trunc('minute', current_timestamp), 1)
                on conflict (client_id, window_started_at)
                do update set request_count=mail_api_request_window.request_count + 1
                returning request_count
                """, Map.of("id", clientId), Integer.class);
        return count != null && count <= limits.getFirst();
    }

    @Transactional
    public void revoke(String clientId, UUID credentialId) {
        if (jdbc.update("""
                update mail_api_credential set revoked_at=current_timestamp
                where id=:key and client_id=:client and revoked_at is null
                """, Map.of("key", credentialId, "client", clientId)) != 1) {
            throw new IllegalArgumentException("credential not found or already revoked");
        }
    }

    @Transactional
    public void setEnabled(String clientId, boolean enabled) {
        if (jdbc.update("update mail_api_client set enabled=:enabled, updated_at=current_timestamp where client_id=:id",
                Map.of("enabled", enabled, "id", clientId)) != 1) {
            throw new IllegalArgumentException("client not found");
        }
    }

    @Transactional
    public void setQuota(String clientId, int requestsPerMinute) {
        if (requestsPerMinute < 1 || requestsPerMinute > 10000) throw new IllegalArgumentException("invalid quota");
        if (jdbc.update("update mail_api_client set requests_per_minute=:rpm, updated_at=current_timestamp where client_id=:id",
                Map.of("rpm", requestsPerMinute, "id", clientId)) != 1) {
            throw new IllegalArgumentException("client not found");
        }
    }

    public List<ClientSummary> clients() {
        return jdbc.query("select client_id, display_name, enabled, requests_per_minute, webhook_allowed_host, daily_transactional_limit, daily_bulk_limit, permissions from mail_api_client order by client_id",
                (rs, row) -> new ClientSummary(rs.getString(1), rs.getString(2), rs.getBoolean(3), rs.getInt(4), rs.getString(5), rs.getInt(6),rs.getInt(7), parsePermissions(rs.getString(8))));
    }

    public Set<String> permissions(String clientId) {
        if (!configured || clientId == null) return Set.of();
        List<String> rows = jdbc.query("select permissions from mail_api_client where client_id=:id and enabled=true",
                Map.of("id", clientId), (rs,row) -> rs.getString(1));
        return rows.isEmpty() ? Set.of() : parsePermissions(rows.getFirst());
    }

    @Transactional
    public void setPermissions(String clientId, Set<String> requested) {
        Set<String> normalized = ClientPermissions.normalize(requested, true);
        if (jdbc.update("update mail_api_client set permissions=:permissions,updated_at=current_timestamp where client_id=:id",
                Map.of("permissions", String.join(",", normalized.stream().sorted().toList()), "id", clientId)) != 1) throw new IllegalArgumentException("client not found");
    }

    private static Set<String> parsePermissions(String raw) {
        if (raw == null || raw.isBlank()) return Set.of();
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (String item : raw.split(",")) if (!item.isBlank()) values.add(item.trim().toUpperCase(java.util.Locale.ROOT));
        return Set.copyOf(values);
    }

    public List<CredentialSummary> credentials(String clientId) {
        return jdbc.query("""
                select id, created_at, expires_at, revoked_at from mail_api_credential
                where client_id=:id order by created_at desc
                """, Map.of("id", clientId), (rs, row) -> new CredentialSummary(
                rs.getObject(1, UUID.class), rs.getTimestamp(2).toInstant(),
                rs.getTimestamp(3) == null ? null : rs.getTimestamp(3).toInstant(),
                rs.getTimestamp(4) == null ? null : rs.getTimestamp(4).toInstant()));
    }

    @Transactional
    public int cleanupExpiredWindows() {
        return jdbc.update("delete from mail_api_request_window where window_started_at < current_timestamp - interval '2 hours'",
                new org.springframework.jdbc.core.namedparam.MapSqlParameterSource());
    }

    private void checkClientId(String clientId) {
        if (!configured || clientId == null || !clientId.matches("[A-Za-z0-9._-]{1,120}") || "admin".equalsIgnoreCase(clientId)) {
            throw new IllegalArgumentException("invalid or reserved client id");
        }
    }

    private String mac(String raw) {
        try {
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(pepper, "HmacSHA256"));
            return HexFormat.of().formatHex(hmac.doFinal(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", e);
        }
    }

    public record IssuedCredential(UUID credentialId, String clientId, String apiKey, Instant expiresAt) {}
    public void setWebhookAllowedHost(String clientId, String host) {
        if (host == null || !host.matches("[a-z0-9][a-z0-9.-]{2,252}") || !host.contains(".")
                || host.contains("..") || host.endsWith(".") || host.matches("[0-9.]+")) {
            throw new IllegalArgumentException("webhook host must be a trusted DNS hostname");
        }
        if (jdbc.update("update mail_api_client set webhook_allowed_host=:host,updated_at=current_timestamp where client_id=:id",
                Map.of("host", host, "id", clientId)) != 1) {
            throw new IllegalArgumentException("client not found");
        }
    }

    public void setDailyLimits(String clientId,Integer transactional,Integer bulk) {
        if(transactional!=null) {
            if(transactional<1||transactional>1000000) throw new IllegalArgumentException("invalid transactional daily quota");
            if(jdbc.update("update mail_api_client set daily_transactional_limit=:limit,updated_at=current_timestamp where client_id=:id",
                    Map.of("limit",transactional,"id",clientId))!=1) throw new IllegalArgumentException("client not found");
        }
        if(bulk!=null) {
            if(bulk<1||bulk>1000000) throw new IllegalArgumentException("invalid bulk daily quota");
            if(jdbc.update("update mail_api_client set daily_bulk_limit=:limit,updated_at=current_timestamp where client_id=:id",
                    Map.of("limit",bulk,"id",clientId))!=1) throw new IllegalArgumentException("client not found");
        }
    }

    public record ClientSummary(String clientId, String displayName, boolean enabled,
                                int requestsPerMinute, String webhookAllowedHost,
                                int dailyTransactionalLimit,int dailyBulkLimit, Set<String> permissions) {}
    public record CredentialSummary(UUID credentialId, Instant createdAt, Instant expiresAt, Instant revokedAt) {}
    private record ActiveCredential(UUID id, String mac) {}
}
