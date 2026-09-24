package com.yeremitech.mailplatform.infrastructure.adapter;

import com.yeremitech.mailplatform.application.port.SensitivePayloadPort;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

public final class JdbcSensitivePayloadAdapter implements SensitivePayloadPort {
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final NamedParameterJdbcTemplate jdbc;
    private final JsonMapper json;
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public JdbcSensitivePayloadAdapter(NamedParameterJdbcTemplate jdbc, JsonMapper json, String base64Key) {
        this.jdbc = jdbc;
        this.json = json;
        byte[] raw = Base64.getDecoder().decode(base64Key);
        if (raw.length != 32) throw new IllegalArgumentException("SENSITIVE_PAYLOAD_KEY must be a Base64 encoded 32-byte key");
        this.key = new SecretKeySpec(raw, "AES");
    }

    @Override
    public void store(UUID messageId, Map<String, Object> values, Instant expiresAt) {
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            byte[] plaintext = json.writeValueAsString(values).getBytes(StandardCharsets.UTF_8);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(messageId.toString().getBytes(StandardCharsets.UTF_8));
            byte[] ciphertext = cipher.doFinal(plaintext);
            MapSqlParameterSource p = new MapSqlParameterSource()
                    .addValue("messageId", messageId)
                    .addValue("nonce", nonce)
                    .addValue("ciphertext", ciphertext)
                    .addValue("expiresAt", JdbcTime.value(expiresAt))
                    .addValue("createdAt", JdbcTime.value(Instant.now()));
            jdbc.update("""
                    insert into mail_sensitive_payload(message_id, nonce, ciphertext, expires_at, created_at)
                    values (:messageId, :nonce, :ciphertext, :expiresAt, :createdAt)
                    on conflict (message_id) do update
                    set nonce=excluded.nonce, ciphertext=excluded.ciphertext, expires_at=excluded.expires_at
                    """, p);
        } catch (Exception ex) {
            throw new IllegalStateException("unable to encrypt sensitive mail payload", ex);
        }
    }

    @Override
    public Map<String, Object> load(UUID messageId) {
        var rows = jdbc.query("""
                select nonce, ciphertext, expires_at
                from mail_sensitive_payload
                where message_id=:messageId and expires_at > :now
                """, Map.of("messageId", messageId, "now", JdbcTime.value(Instant.now())), (rs, rowNum) -> {
            try {
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, rs.getBytes("nonce")));
                cipher.updateAAD(messageId.toString().getBytes(StandardCharsets.UTF_8));
                byte[] clear = cipher.doFinal(rs.getBytes("ciphertext"));
                return json.readValue(new String(clear, StandardCharsets.UTF_8), new TypeReference<Map<String, Object>>() {});
            } catch (Exception ex) {
                throw new IllegalStateException("unable to decrypt sensitive mail payload", ex);
            }
        });
        return rows.isEmpty() ? Map.of() : new HashMap<>(rows.getFirst());
    }

    @Override
    public void purge(UUID messageId) {
        jdbc.update("delete from mail_sensitive_payload where message_id=:messageId", Map.of("messageId", messageId));
    }

    @Override
    public int purgeExpired(Instant now, int limit) {
        return jdbc.update("""
                delete from mail_sensitive_payload
                where message_id in (
                    select message_id from mail_sensitive_payload
                    where expires_at <= :now
                    order by expires_at
                    limit :limit
                )
                """, Map.of("now", JdbcTime.value(now), "limit", limit));
    }
}
