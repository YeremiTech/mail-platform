package com.yeremitech.mailplatform.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.yeremitech.mailplatform.application.model.*;
import com.yeremitech.mailplatform.application.port.*;
import com.yeremitech.mailplatform.domain.*;
import java.io.ByteArrayInputStream;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class MailDeliveryWorkerTest {
    @Test
    void duplicatedBrokerDeliveryCallsProviderOnlyOnceWhenClaimIsAtomic() {
        UUID id=UUID.randomUUID(); Instant now=Instant.parse("2026-09-23T12:00:00Z");
        MailMessageData message=new MailMessageData(id,"inventory","k","s","t",Map.of(),List.of(new MailRecipient(new EmailAddress("a@b.com"),RecipientType.TO,null)),List.of(),MailPriority.TRANSACTIONAL,MailStatus.PROCESSING,null,now,now,null,null);
        AtomicInteger claims=new AtomicInteger(); AtomicInteger sends=new AtomicInteger();
        MailProcessingPort processing=new MemoryProcessing(message) {
            @Override public Optional<MailMessageData> claim(UUID messageId, UUID token, Instant at) {
                return claims.getAndIncrement()==0 ? Optional.of(message) : Optional.empty();
            }
        };
        MailProviderPort provider=new MailProviderPort(){public String providerKey(){return "test";} public DeliveryResult send(PreparedMail mail){sends.incrementAndGet();return new DeliveryResult("p1");}};
        DeliveryAttemptRepository attempts=new MemoryAttempts();
        MailDeliveryWorker worker=new MailDeliveryWorker(processing,(k,v)->new TemplateRendererPort.RenderedTemplate("<p>x</p>","x"),provider,new EmptyStorage(),new EmptySensitive(),null,attempts,new MailRetryPort(){public boolean scheduleRetry(UUID a,UUID t,MailPriority b,Instant c,String d,Instant e){return true;} public int recoverStaleProcessing(Instant a,Instant b,int c){return 0;}},Clock.fixed(now,ZoneOffset.UTC),5);
        worker.process(id.toString()); worker.process(id.toString());
        assertEquals(1,sends.get());
    }

    @Test
    void expiredRecoveryPayloadDoesNotSendBlankCode() {
        UUID id = UUID.randomUUID(); UUID challengeId = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-23T12:00:00Z");
        MailMessageData message = new MailMessageData(id, "inventory", "password-recovery:" + challengeId,
                "Código", "security/password-reset", Map.of("expiresMinutes", 10),
                List.of(new MailRecipient(new EmailAddress("a@b.com"), RecipientType.TO, null)),
                List.of(), MailPriority.SECURITY, MailStatus.PROCESSING, null, now, now, null, null);
        PasswordChallengeData challenge = new PasswordChallengeData(challengeId, "inventory", "user-1", "a@b.com",
                "hash", ChallengeStatus.ACTIVE, 0, 5, now.plusSeconds(600), now, null);
        PasswordRecoveryRepository recovery = (PasswordRecoveryRepository) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{PasswordRecoveryRepository.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("findChallenge")) return Optional.of(challenge);
                    throw new UnsupportedOperationException(method.getName());
                });
        AtomicInteger sends = new AtomicInteger();
        MailProviderPort provider = new MailProviderPort() {
            public String providerKey() { return "test"; }
            public DeliveryResult send(PreparedMail mail) { sends.incrementAndGet(); return new DeliveryResult("p1"); }
        };
        AtomicInteger failedUpdates = new AtomicInteger();
        MailProcessingPort processing = new MemoryProcessing(message) {
            @Override public boolean markFailed(UUID id, UUID token, String error, Instant at) {
                failedUpdates.incrementAndGet();
                return true;
            }
        };
        MailDeliveryWorker worker = new MailDeliveryWorker(processing,
                (key, variables) -> new TemplateRendererPort.RenderedTemplate("<p>x</p>", "x"), provider,
                new EmptyStorage(), new EmptySensitive(), recovery, new MemoryAttempts(),
                new MailRetryPort() { public boolean scheduleRetry(UUID a, UUID t, MailPriority b, Instant c, String d, Instant e) { return true; }
                    public int recoverStaleProcessing(Instant a, Instant b, int c) { return 0; } },
                Clock.fixed(now, ZoneOffset.UTC), 5);
        worker.process(id.toString());
        assertEquals(0, sends.get());
        assertEquals(1, failedUpdates.get());
    }

    @Test
    void recoveredClaimIsNotSubmittedToSmtp() {
        UUID id = UUID.randomUUID(); Instant now = Instant.parse("2026-09-23T12:00:00Z");
        MailMessageData message = new MailMessageData(id, "inventory", "k", "s", "t", Map.of(),
                List.of(new MailRecipient(new EmailAddress("a@b.com"), RecipientType.TO, null)),
                List.of(), MailPriority.TRANSACTIONAL, MailStatus.PROCESSING, null, now, now, null, null);
        AtomicInteger sends = new AtomicInteger();
        MailProviderPort provider = new MailProviderPort() {
            public String providerKey() { return "test"; }
            public DeliveryResult send(PreparedMail mail) { sends.incrementAndGet(); return new DeliveryResult("p1"); }
        };
        MailProcessingPort processing = new MemoryProcessing(message) {
            @Override public boolean isCurrentClaim(UUID messageId, UUID token) { return false; }
        };
        MailDeliveryWorker worker = new MailDeliveryWorker(processing,
                (k,v) -> new TemplateRendererPort.RenderedTemplate("<p>x</p>", "x"), provider,
                new EmptyStorage(), new EmptySensitive(), null, new MemoryAttempts(),
                new MailRetryPort() {
                    public boolean scheduleRetry(UUID a, UUID t, MailPriority b, Instant c, String d, Instant e) { return true; }
                    public int recoverStaleProcessing(Instant a, Instant b, int c) { return 0; }
                }, Clock.fixed(now, ZoneOffset.UTC), 5);
        worker.process(id.toString());
        assertEquals(0, sends.get());
    }
    @Test
    void optOutAfterQueueButBeforeSmtpFailsClosed() {
        UUID id=UUID.randomUUID(); UUID batchId=UUID.randomUUID();
        Instant now=Instant.parse("2026-09-23T12:00:00Z");
        MailMessageData message=new MailMessageData(id,"tenant","campaign:"+batchId+":0", "Promo", "custom/promo/v1",
                Map.of(),List.of(new MailRecipient(new EmailAddress("optout@example.test"),RecipientType.TO,null)),
                List.of(),MailPriority.BULK,MailStatus.PROCESSING,batchId,now,now,null,null);
        AtomicInteger sends=new AtomicInteger(); AtomicInteger failures=new AtomicInteger();
        MailProviderPort provider=new MailProviderPort(){public String providerKey(){return "test";}
            public DeliveryResult send(PreparedMail mail){sends.incrementAndGet();return new DeliveryResult("p1");}};
        MailProcessingPort processing=new MemoryProcessing(message){
            @Override public boolean markFailed(UUID mid,UUID token,String reason,Instant at){
                if(reason.contains("marketing recipient is suppressed")) failures.incrementAndGet();
                return true;
            }
        };
        MailDeliveryWorker worker=new MailDeliveryWorker(processing,
                (key,vars)->new TemplateRendererPort.RenderedTemplate("<p>Hi</p>","Hi"),provider,
                new EmptyStorage(),new EmptySensitive(),null,new MemoryAttempts(),
                new MailRetryPort(){public boolean scheduleRetry(UUID a,UUID t,MailPriority b,Instant c,String d,Instant e){return true;}
                    public int recoverStaleProcessing(Instant a,Instant b,int c){return 0;}},
                Clock.fixed(now,ZoneOffset.UTC),5, candidate->false);
        worker.process(id.toString());
        assertEquals(0,sends.get());
        assertEquals(1,failures.get());
    }

    @Test
    void marketingMessagesContainLinkInHtmlAndTextEvenIfTemplateOmitsIt() {
        UUID id=UUID.randomUUID(); UUID batchId=UUID.randomUUID();
        Instant now=Instant.parse("2026-09-23T12:00:00Z");
        String url="https://mail.example.org/api/v1/public/unsubscribe?token="
                + com.yeremitech.mailplatform.domain.UnsubscribeToken.issue();
        MailMessageData message=new MailMessageData(id,"tenant","campaign:"+batchId+":1",
                "Special offer","custom/offer/v1",Map.of("unsubscribeUrl",url),
                List.of(new MailRecipient(new EmailAddress("recipient@example.test"),RecipientType.TO,null)),
                List.of(),MailPriority.BULK,MailStatus.PROCESSING,batchId,now,now,null,null);
        java.util.concurrent.atomic.AtomicReference<PreparedMail> delivered=new java.util.concurrent.atomic.AtomicReference<>();
        MailProviderPort provider=new MailProviderPort(){
            public String providerKey(){return "test";}
            public DeliveryResult send(PreparedMail mail){ delivered.set(mail); return new DeliveryResult("accepted"); }
        };
        DeliveryPolicyPort marketing=new DeliveryPolicyPort(){
            public boolean mayDeliver(MailMessageData candidate){return true;}
            public boolean isMarketing(MailMessageData candidate){return true;}
        };
        MailDeliveryWorker worker=new MailDeliveryWorker(new MemoryProcessing(message),
                (key,vars)->new TemplateRendererPort.RenderedTemplate("<p>Offer</p>","Offer"),
                provider,new EmptyStorage(),new EmptySensitive(),null,new MemoryAttempts(),
                new MailRetryPort(){public boolean scheduleRetry(UUID a,UUID b,MailPriority c,Instant d,String e,Instant f){return true;}
                    public int recoverStaleProcessing(Instant a,Instant b,int c){return 0;}},
                Clock.fixed(now,ZoneOffset.UTC),5,marketing);
        worker.process(id.toString());
        org.junit.jupiter.api.Assertions.assertNotNull(delivered.get());
        org.junit.jupiter.api.Assertions.assertTrue(delivered.get().htmlBody().contains(url));
        org.junit.jupiter.api.Assertions.assertTrue(delivered.get().textBody().contains(url));
        org.junit.jupiter.api.Assertions.assertEquals(url, delivered.get().oneClickUnsubscribeUrl());
    }

    @Test
    void marketingWithoutUnsubscribeLinkIsRejectedBeforeSmtp() {
        UUID id=UUID.randomUUID(); UUID batchId=UUID.randomUUID();
        Instant now=Instant.parse("2026-09-23T12:00:00Z");
        MailMessageData message=new MailMessageData(id,"tenant","campaign:"+batchId+":1",
                "Offer","custom/offer/v1",Map.of(),
                List.of(new MailRecipient(new EmailAddress("recipient@example.test"),RecipientType.TO,null)),
                List.of(),MailPriority.BULK,MailStatus.PROCESSING,batchId,now,now,null,null);
        AtomicInteger sent=new AtomicInteger();
        MailProviderPort provider=new MailProviderPort(){public String providerKey(){return "test";}
            public DeliveryResult send(PreparedMail mail){sent.incrementAndGet();return new DeliveryResult("accepted");}};
        DeliveryPolicyPort marketing=new DeliveryPolicyPort(){public boolean mayDeliver(MailMessageData c){return true;}
            public boolean isMarketing(MailMessageData c){return true;}};
        MailDeliveryWorker worker=new MailDeliveryWorker(new MemoryProcessing(message),
                (key,vars)->new TemplateRendererPort.RenderedTemplate("<p>Offer</p>","Offer"),
                provider,new EmptyStorage(),new EmptySensitive(),null,new MemoryAttempts(),
                new MailRetryPort(){public boolean scheduleRetry(UUID a,UUID b,MailPriority c,Instant d,String e,Instant f){return true;}
                    public int recoverStaleProcessing(Instant a,Instant b,int c){return 0;}},
                Clock.fixed(now,ZoneOffset.UTC),5,marketing);
        worker.process(id.toString());
        assertEquals(0,sent.get());
    }

    @Test
    void attachmentIsDiskBackedForSmtpAndDeletedAfterSend() throws Exception {
        UUID id = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-23T12:00:00Z");
        byte[] payload = new byte[512 * 1024];
        new java.util.Random(7).nextBytes(payload);
        String sha = java.util.HexFormat.of().formatHex(java.security.MessageDigest
                .getInstance("SHA-256").digest(payload));
        var ref = new AttachmentRef(attachmentId, "tenant", "proof.pdf", "application/pdf",
                payload.length, "lo:123", sha);
        AttachmentStoragePort storage = new AttachmentStoragePort() {
            public AttachmentRef store(String c, String f, String t, java.io.InputStream in, long size) {
                throw new UnsupportedOperationException();
            }
            public Optional<StoredAttachment> load(String c, UUID i) {
                return "tenant".equals(c) && attachmentId.equals(i)
                        ? Optional.of(new StoredAttachment(ref, new ByteArrayInputStream(payload)))
                        : Optional.empty();
            }
            public boolean belongsTo(String c, UUID i) { return attachmentId.equals(i) && "tenant".equals(c); }
        };
        var message = new MailMessageData(id, "tenant", "attach-test", "Proof", "custom/proof/v1",
                Map.of(), List.of(new MailRecipient(new EmailAddress("x@example.test"), RecipientType.TO, null)),
                List.of(attachmentId), MailPriority.TRANSACTIONAL, MailStatus.PROCESSING, null,
                now, now, null, null);
        java.util.concurrent.atomic.AtomicReference<java.nio.file.Path> duringSend = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<byte[]> received = new java.util.concurrent.atomic.AtomicReference<>();
        MailProviderPort provider = new MailProviderPort() {
            public String providerKey() { return "test"; }
            public DeliveryResult send(PreparedMail mail) {
                try {
                    java.nio.file.Path path = mail.attachments().getFirst().path();
                    org.junit.jupiter.api.Assertions.assertTrue(java.nio.file.Files.exists(path));
                    duringSend.set(path);
                    received.set(java.nio.file.Files.readAllBytes(path));
                    return new DeliveryResult("accepted");
                } catch (Exception ex) { throw new IllegalStateException(ex); }
            }
        };
        var worker = new MailDeliveryWorker(new MemoryProcessing(message),
                (key, vars) -> new TemplateRendererPort.RenderedTemplate("<p>Proof</p>", "Proof"),
                provider, storage, new EmptySensitive(), null, new MemoryAttempts(),
                new MailRetryPort() {
                    public boolean scheduleRetry(UUID a, UUID b, MailPriority c, Instant d, String e, Instant f) { return true; }
                    public int recoverStaleProcessing(Instant a, Instant b, int c) { return 0; }
                }, Clock.fixed(now, ZoneOffset.UTC), 5);
        worker.process(id.toString());
        org.junit.jupiter.api.Assertions.assertArrayEquals(payload, received.get());
        org.junit.jupiter.api.Assertions.assertNotNull(duringSend.get());
        org.junit.jupiter.api.Assertions.assertFalse(java.nio.file.Files.exists(duringSend.get()));
    }

    private static class MemoryProcessing implements MailProcessingPort {
        private final MailMessageData message;
        MemoryProcessing(MailMessageData message) { this.message = message; }
        public Optional<MailMessageData> claim(UUID id, UUID token, Instant at) { return Optional.of(message); }
        public boolean isCurrentClaim(UUID id, UUID token) { return true; }
        public boolean heartbeat(UUID id, UUID token, Instant at) { return true; }
        public boolean markSent(UUID id, UUID token, String providerId, Instant at) { return true; }
        public boolean markFailed(UUID id, UUID token, String error, Instant at) { return true; }
    }
    private static final class MemoryAttempts implements DeliveryAttemptRepository { int n; public int nextAttemptNumber(UUID id){return ++n;} public DeliveryAttemptData save(DeliveryAttemptData d){return d;} public List<DeliveryAttemptData> findByMessageId(UUID id){return List.of();} }
    private static final class EmptyStorage implements AttachmentStoragePort { public AttachmentRef store(String c,String f,String t,java.io.InputStream i,long s){throw new UnsupportedOperationException();} public Optional<StoredAttachment> load(String c,UUID id){return Optional.empty();} public boolean belongsTo(String c,UUID id){return false;} }
    private static final class EmptySensitive implements SensitivePayloadPort { public void store(UUID id,Map<String,Object> v,Instant e){} public Map<String,Object> load(UUID id){return Map.of();} public void purge(UUID id){} public int purgeExpired(Instant n,int l){return 0;} }
}
