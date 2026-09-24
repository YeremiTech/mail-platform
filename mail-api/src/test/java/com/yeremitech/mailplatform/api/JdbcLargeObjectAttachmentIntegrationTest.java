package com.yeremitech.mailplatform.api;

import com.yeremitech.mailplatform.application.port.AttachmentStoragePort;
import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import javax.sql.DataSource;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Real PostgreSQL tests; enabled by the CI database environment. */
@SpringBootTest(properties={"app.worker.enabled=false","spring.rabbitmq.listener.simple.auto-startup=false"})
@EnabledIfEnvironmentVariable(named="MAIL_PLATFORM_SMOKE_DB_URL", matches=".+")
class JdbcLargeObjectAttachmentIntegrationTest {
    @Autowired AttachmentStoragePort storage;
    @Autowired DataSource dataSource;
    @Autowired NamedParameterJdbcTemplate jdbc;

    @Test void streamingRoundtripIsolationAndLargeObjectCleanup() throws Exception {
        byte[] bytes = new byte[1024 * 1024];
        new java.util.Random(42).nextBytes(bytes);
        String client = "lo-fixture-" + UUID.randomUUID();
        var stored = storage.store(client, "report.pdf", "application/pdf",
                new ByteArrayInputStream(bytes), bytes.length);
        long oid = oid(stored.id());
        try {
            assertTrue(oid > 0);
            assertEquals(0L, java.util.Objects.requireNonNull(jdbc.queryForObject("""
                    select count(*) from information_schema.columns
                    where table_schema=current_schema() and table_name='mail_attachment' and column_name='content'
                    """, Map.of(), Long.class)).longValue(), "V17 must remove the BYTEA column");
            assertTrue(storage.load("another-client", stored.id()).isEmpty());
            try (var stream = storage.load(client, stored.id()).orElseThrow().content()) {
                byte[] actual = stream.readAllBytes(); // Bounded 1-MiB test fixture only.
                assertArrayEquals(bytes, actual);
                assertEquals(stored.checksumSha256(), HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(actual)));
            }
        } finally {
            jdbc.update("delete from mail_attachment where id=:id", Map.of("id", stored.id()));
        }
        assertLargeObjectAbsent(oid);
    }

    @Test void rejectedSizeRollsBackBothMetadataAndLargeObject() {
        String client = "rollback-fixture-" + UUID.randomUUID();
        long before = largeObjectCount();
        byte[] content = new byte[8192];
        assertThrows(IllegalArgumentException.class,
                () -> storage.store(client, "test.txt", "text/plain",
                        new ByteArrayInputStream(content), content.length - 1));
        assertEquals(0L, java.util.Objects.requireNonNull(jdbc.queryForObject(
                "select count(*) from mail_attachment where client_id=:client", Map.of("client", client), Long.class)).longValue());
        assertEquals(before, largeObjectCount(), "a failed upload must not leak a PostgreSQL Large Object");
    }

    @Test void deletionWaitsForOpenStreamingReaderThenUnlinksLargeObject() throws Exception {
        String client = "reader-fixture-" + UUID.randomUUID();
        var stored = storage.store(client, "reader.bin", "application/octet-stream",
                new ByteArrayInputStream(new byte[]{1, 2, 3, 4}), 4);
        long oid = oid(stored.id());
        var stream = storage.load(client, stored.id()).orElseThrow().content();
        CountDownLatch started = new CountDownLatch(1);
        try {
            CompletableFuture<Integer> deletion = CompletableFuture.supplyAsync(() -> {
                // Acquire a second *real* PostgreSQL connection before signaling the reader.
                try (var conn = dataSource.getConnection();
                     var statement = conn.prepareStatement("delete from mail_attachment where id=?")) {
                    statement.setObject(1, stored.id());
                    started.countDown();
                    return statement.executeUpdate();
                } catch (java.sql.SQLException error) {
                    throw new IllegalStateException("concurrent delete failed", error);
                }
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            try {
                // The FOR SHARE row lock must prevent deletion while the LO stream holds its transaction.
                Thread.sleep(150);
                assertFalse(deletion.isDone(), "deletion bypassed the active streaming reader");
                assertEquals(1, stream.read());
            } finally {
                stream.close();
            }
            assertEquals(1, deletion.get(10, TimeUnit.SECONDS));
        } finally {
            stream.close();
            jdbc.update("delete from mail_attachment where id=:id", Map.of("id", stored.id()));
        }
        assertLargeObjectAbsent(oid);
    }

    private long oid(UUID id) {
        Long value = jdbc.queryForObject("select content_oid::bigint from mail_attachment where id=:id",
                Map.of("id", id), Long.class);
        return java.util.Objects.requireNonNull(value);
    }

    private long largeObjectCount() {
        return java.util.Objects.requireNonNull(jdbc.queryForObject(
                "select count(*) from pg_largeobject_metadata", Map.of(), Long.class));
    }

    private void assertLargeObjectAbsent(long oid) {
        assertEquals(0L, java.util.Objects.requireNonNull(jdbc.queryForObject(
                "select count(*) from pg_largeobject_metadata where oid=cast(:oid as oid)",
                Map.of("oid", oid), Long.class)).longValue());
    }
}
