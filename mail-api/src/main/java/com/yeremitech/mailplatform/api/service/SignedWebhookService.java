package com.yeremitech.mailplatform.api.service;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Durable at-least-once callbacks. Only an administrator can assign each tenant's exact HTTPS host. */
@Service
public class SignedWebhookService {
    private static final Logger log = LoggerFactory.getLogger(SignedWebhookService.class);
    private static final SecureRandom RNG = new SecureRandom();
    private static final int MAX_ATTEMPTS = 8;
    private final NamedParameterJdbcTemplate jdbc;
    private final JsonMapper json;
    private final SecretKeySpec encryptionKey;
    private final PinnedWebhookTransport http;
    private final int batchSize;

    public SignedWebhookService(NamedParameterJdbcTemplate jdbc,JsonMapper json,
            @Value("${app.security.webhook-encryption-key:}") String encodedKey,
            @Value("${app.webhooks.batch-size:10}") int batchSize){
        this.jdbc=jdbc;this.json=json;
        if(batchSize<1 || batchSize>100) throw new IllegalArgumentException("webhook batch-size must be 1..100");
        this.batchSize=batchSize;
        if(encodedKey==null || encodedKey.isBlank()) encryptionKey=null;
        else {
            byte[] raw=Base64.getDecoder().decode(encodedKey);
            if(raw.length!=32) throw new IllegalArgumentException("WEBHOOK_ENCRYPTION_KEY must be 32 bytes, Base64 encoded");
            encryptionKey=new SecretKeySpec(raw,"AES");
        }
        http = new PinnedWebhookTransport();
    }

    @Transactional
    public CreatedSubscription configure(String clientId,String endpoint) {
        requireEncryptionKey();
        URI uri=validateEndpoint(clientId,endpoint);
        byte[] raw=new byte[32]; RNG.nextBytes(raw);
        String secret=Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        byte[] nonce=new byte[12]; RNG.nextBytes(nonce);
        byte[] encrypted=encrypt(clientId,nonce,raw);
        jdbc.update("""
                insert into mail_webhook_subscription(client_id,endpoint,nonce,encrypted_secret,enabled)
                values (:client,:endpoint,:nonce,:encrypted,true)
                on conflict (client_id) do update set endpoint=excluded.endpoint,
                    nonce=excluded.nonce,encrypted_secret=excluded.encrypted_secret,
                    enabled=true,updated_at=current_timestamp
                """,new MapSqlParameterSource().addValue("client",clientId)
                .addValue("endpoint",uri.toASCIIString()).addValue("nonce",nonce).addValue("encrypted",encrypted));
        return new CreatedSubscription(uri.toASCIIString(),secret,true);
    }

    public Subscription summary(String clientId) {
        List<Subscription> rows=jdbc.query("""
                select endpoint,enabled,created_at,updated_at from mail_webhook_subscription
                where client_id=:client
                """,Map.of("client",clientId),(rs,row)->new Subscription(rs.getString(1),rs.getBoolean(2),
                rs.getTimestamp(3).toInstant(),rs.getTimestamp(4).toInstant()));
        if(rows.isEmpty()) throw new NoSuchElementException("webhook subscription not found");
        return rows.getFirst();
    }

    @Transactional
    public void disable(String clientId) {
        if(jdbc.update("""
                update mail_webhook_subscription set enabled=false,updated_at=current_timestamp
                where client_id=:client
                """,Map.of("client",clientId))!=1) throw new NoSuchElementException("webhook subscription not found");
        jdbc.update("""
                update mail_webhook_delivery set status='SKIPPED',last_error='Subscription disabled'
                where client_id=:client and status in ('PENDING','RETRYING')
                """,Map.of("client",clientId));
    }

    public List<DeliverySummary> deliveries(String clientId,int limit) {
        if(limit<1||limit>100) throw new IllegalArgumentException("limit must be 1..100");
        return jdbc.query("""
                select id,message_id,event_type,status,attempts,occurred_at,delivered_at,last_status_code
                from mail_webhook_delivery where client_id=:client
                order by occurred_at desc limit :limit
                """,Map.of("client",clientId,"limit",limit),
                (rs,row)->new DeliverySummary(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),
                rs.getString(3),rs.getString(4),rs.getInt(5),rs.getTimestamp(6).toInstant(),
                instantOrNull(rs.getTimestamp(7)),rs.getObject(8,Integer.class)));
    }

    /** Claim commits before HTTP; a crashed node's lease expires and another node can retry. */
    @Transactional
    public List<Pending> claim() {
        Instant now=Instant.now();
        Instant lease=now.plus(Duration.ofSeconds(60));
        return jdbc.query("""
                with picked as (
                    select id from mail_webhook_delivery
                    where ((status in ('PENDING','RETRYING') and next_attempt_at<=:now)
                        or (status='DELIVERING' and locked_until<=:now))
                    order by next_attempt_at,id for update skip locked limit :limit
                )
                update mail_webhook_delivery e set status='DELIVERING',locked_until=:lease
                from picked where e.id=picked.id
                returning e.id,e.message_id,e.client_id,e.event_type,e.occurred_at,e.attempts,e.locked_until
                """,new MapSqlParameterSource().addValue("now",Timestamp.from(now))
                .addValue("lease",Timestamp.from(lease)).addValue("limit",batchSize),
                (rs,row)->new Pending(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getString(3),
                rs.getString(4),rs.getTimestamp(5).toInstant(),rs.getInt(6),rs.getTimestamp(7).toInstant()));
    }

    public void dispatchClaimed(Pending event) {
        Integer code=null;
        String error=null;
        try {
            List<Receiver> rows=jdbc.query("""
                    select s.endpoint,s.nonce,s.encrypted_secret,c.webhook_allowed_host
                    from mail_webhook_subscription s join mail_api_client c on c.client_id=s.client_id
                    where s.client_id=:client and s.enabled=true and c.enabled=true
                    """,Map.of("client",event.clientId()),(rs,row)->new Receiver(rs.getString(1),
                    rs.getBytes(2),rs.getBytes(3),rs.getString(4)));
            if(rows.isEmpty()) { complete(event,null,"Subscription unavailable",false,true); return; }
            Receiver receiver=rows.getFirst();
            URI uri=checkedUri(receiver.endpoint(),receiver.allowedHost());
            byte[] secret=decrypt(event.clientId(),receiver.nonce(),receiver.encrypted());
            List<String> providerIds=jdbc.query("select provider_message_id from mail_message where id=:id",
                    Map.of("id",event.messageId()),(rs,row)->rs.getString(1));
            java.util.Map<String,Object> payload=new java.util.LinkedHashMap<>();
            payload.put("eventId",event.id().toString());
            payload.put("messageId",event.messageId().toString());
            payload.put("clientId",event.clientId());
            payload.put("type",event.type());
            payload.put("occurredAt",event.occurredAt().toString());
            if(!providerIds.isEmpty() && providerIds.getFirst()!=null) payload.put("providerMessageId",providerIds.getFirst());
            String jsonBody=json.writeValueAsString(payload);
            String timestamp=String.valueOf(Instant.now().getEpochSecond());
            String signature=sign(secret,timestamp+"."+jsonBody);
            code = http.post(uri, jsonBody.getBytes(StandardCharsets.UTF_8),
                    event.id().toString(), timestamp, signature);
            boolean success=code>=200&&code<300;
            boolean retry=code>=500||code==408||code==429;
            complete(event,code,success?null:"HTTP "+code,success,!success&&!retry);
        } catch(Exception ex) {
            error=ex.getClass().getSimpleName();
            log.warn("Webhook delivery {} failed: {}",event.id(),error);
            complete(event,code,error,false,false);
        }
    }

    @Transactional
    public void complete(Pending event,Integer httpStatus,String error,boolean succeeded,boolean permanent) {
        int attempts=event.attempts()+1;
        String state=succeeded?"DELIVERED":permanent||attempts>=MAX_ATTEMPTS?"DEAD":"RETRYING";
        Instant now=Instant.now();
        long backoff=Math.min(3600L,10L*(1L<<Math.min(attempts,8)));
        jdbc.update("""
                update mail_webhook_delivery set status=:status,attempts=:attempts,
                    next_attempt_at=:next,locked_until=null,last_status_code=:code,
                    last_error=:error,delivered_at=:delivered
                where id=:id and status='DELIVERING' and locked_until=:lease
                """,new MapSqlParameterSource().addValue("status",state).addValue("attempts",attempts)
                .addValue("next",Timestamp.from(now.plusSeconds(backoff)))
                .addValue("code",httpStatus).addValue("error",error==null?null:error.substring(0,Math.min(error.length(),500)))
                .addValue("delivered",succeeded?Timestamp.from(now):null)
                .addValue("id",event.id()).addValue("lease",Timestamp.from(event.lockedUntil())));
    }

    private URI validateEndpoint(String clientId,String raw) {
        List<String> allowed=jdbc.query("""
                select webhook_allowed_host from mail_api_client
                where client_id=:client and enabled=true
                """,Map.of("client",clientId),(rs,row)->rs.getString(1));
        if(allowed.isEmpty()||allowed.getFirst()==null) {
            throw new IllegalArgumentException("administrator must assign a trusted webhook_allowed_host first");
        }
        URI uri=checkedUri(raw,allowed.getFirst());
        WebhookAddressPolicy.verifyPublicAddress(uri.getHost());
        return uri;
    }

    private static URI checkedUri(String raw,String allowedHost) {
        URI uri;
        try { uri=URI.create(raw); }
        catch(Exception ex) { throw new IllegalArgumentException("invalid webhook URI",ex); }
        String host=uri.getHost();
        if(!"https".equalsIgnoreCase(uri.getScheme())||host==null||allowedHost==null
                ||!host.equalsIgnoreCase(allowedHost)||uri.getRawUserInfo()!=null||uri.getRawFragment()!=null
                ||(uri.getPort()!=-1&&uri.getPort()!=443)||raw.length()>2048) {
            throw new IllegalArgumentException("webhook must use the administrator-approved HTTPS host");
        }
        return uri;
    }

    private void requireEncryptionKey() {
        if(encryptionKey==null) throw new IllegalStateException("WEBHOOK_ENCRYPTION_KEY is not configured");
    }
    private byte[] encrypt(String client,byte[] nonce,byte[] raw) {
        requireEncryptionKey();
        try {
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,encryptionKey,new GCMParameterSpec(128,nonce));
            cipher.updateAAD(client.getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(raw);
        } catch(GeneralSecurityException ex) { throw new IllegalStateException("webhook encryption failed",ex); }
    }
    private byte[] decrypt(String client,byte[] nonce,byte[] encrypted) {
        requireEncryptionKey();
        try {
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,encryptionKey,new GCMParameterSpec(128,nonce));
            cipher.updateAAD(client.getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(encrypted);
        } catch(GeneralSecurityException ex) { throw new IllegalStateException("webhook decryption failed",ex); }
    }
    private static String sign(byte[] secret,String data) {
        try {
            Mac mac=Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret,"HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch(GeneralSecurityException ex) { throw new IllegalStateException("webhook signing failed",ex); }
    }

    private static Instant instantOrNull(Timestamp ts){return ts==null?null:ts.toInstant();}
    public record CreatedSubscription(String endpoint,String signingSecret,boolean enabled){}
    public record Subscription(String endpoint,boolean enabled,Instant createdAt,Instant updatedAt){}
    public record DeliverySummary(UUID eventId,UUID messageId,String eventType,String status,int attempts,
                                  Instant occurredAt,Instant deliveredAt,Integer lastHttpStatus){}
    public record Pending(UUID id,UUID messageId,String clientId,String type,Instant occurredAt,int attempts,Instant lockedUntil){}
    private record Receiver(String endpoint,byte[] nonce,byte[] encrypted,String allowedHost){}
}
