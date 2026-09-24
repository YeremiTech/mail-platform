package com.yeremitech.mailplatform.api;

import com.yeremitech.mailplatform.application.security.TokenHasher;
import com.yeremitech.mailplatform.application.usecase.ConsumeResetGrantUseCase;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Concurrent HTTP services must not consume a password reset grant more than once. */
@SpringBootTest(properties={"app.worker.enabled=false","spring.rabbitmq.listener.simple.auto-startup=false"})
@EnabledIfEnvironmentVariable(named="MAIL_PLATFORM_SMOKE_DB_URL",matches=".+")
class PasswordRecoveryConcurrencyIntegrationTest {
    @Autowired ConsumeResetGrantUseCase consume;
    @Autowired TokenHasher hasher;
    @Autowired NamedParameterJdbcTemplate jdbc;

    @Test void onlyOneConcurrentConsumerWins() throws Exception {
        UUID challenge=UUID.randomUUID(), grant=UUID.randomUUID();
        String client="otp-race-"+UUID.randomUUID();
        String token=UUID.randomUUID().toString();
        Instant now=Instant.now();
        jdbc.update("""
                insert into password_reset_challenge(id,client_id,subject_reference,email,otp_hash,status,
                    failed_attempts,max_attempts,expires_at,created_at)
                values (:id,:client,'subject','recipient@example.test',:hash,'CONSUMED',0,5,:expires,:created)
                """,new MapSqlParameterSource().addValue("id",challenge).addValue("client",client)
                .addValue("hash",hasher.hash(challenge.toString(),"123456"))
                .addValue("expires",java.sql.Timestamp.from(now.plusSeconds(300)))
                .addValue("created",java.sql.Timestamp.from(now.minusSeconds(1))));
        jdbc.update("""
                insert into password_reset_grant(id,challenge_id,client_id,subject_reference,token_hash,
                    expires_at,created_at)
                values (:id,:challenge,:client,'subject',:hash,:expires,:created)
                """,new MapSqlParameterSource().addValue("id",grant).addValue("challenge",challenge)
                .addValue("client",client).addValue("hash",hasher.hash(grant.toString(),token))
                .addValue("expires",java.sql.Timestamp.from(now.plusSeconds(300)))
                .addValue("created",java.sql.Timestamp.from(now)));
        try (var pool=Executors.newFixedThreadPool(10)) {
            CountDownLatch ready=new CountDownLatch(10), start=new CountDownLatch(1);
            java.util.List<java.util.concurrent.Future<Boolean>> results=new java.util.ArrayList<>();
            for(int i=0;i<10;i++) {
                results.add(pool.submit(() -> {
                    ready.countDown(); start.await();
                    try { consume.execute(client,grant,token); return true; }
                    catch (IllegalArgumentException rejected) { return false; }
                }));
            }
            assertTrue(ready.await(10,java.util.concurrent.TimeUnit.SECONDS));
            start.countDown();
            int success=0;
            for(var result:results) if(result.get(15,java.util.concurrent.TimeUnit.SECONDS)) success++;
            assertEquals(1,success,"exactly one consumer may claim a reset grant");
        } finally {
            jdbc.update("delete from password_reset_grant where id=:id",Map.of("id",grant));
            jdbc.update("delete from password_reset_challenge where id=:id",Map.of("id",challenge));
        }
    }
}
