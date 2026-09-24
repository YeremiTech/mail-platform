package com.yeremitech.mailplatform.api;

import java.sql.Connection;
import java.sql.DriverManager;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises fresh installation and supported upgrade histories in isolated PostgreSQL schemas. */
@EnabledIfEnvironmentVariable(named="MAIL_PLATFORM_SMOKE_DB_URL", matches=".+")
class FlywayMigrationIntegrationTest {
    private final String url = System.getenv("MAIL_PLATFORM_SMOKE_DB_URL");
    private final String user = System.getenv("DB_USERNAME");
    private final String password = System.getenv("DB_PASSWORD");

    @Test void freshInstallAndV1V12V15V16UpgradeMatrix() throws Exception {
        for (int previousVersion : new int[]{0, 1, 12, 15, 16}) {
            verifyHistory(previousVersion);
        }
    }

    private void verifyHistory(int previousVersion) throws Exception {
        String schema = "mail_ci_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection root = DriverManager.getConnection(url, user, password);
             var stmt = root.createStatement()) {
            stmt.execute("create schema " + schema);
            String isolatedUrl = url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema;
            UUID legacyId = UUID.randomUUID();
            UUID alreadyLargeObjectId = UUID.randomUUID();
            byte[] legacyBytes = new byte[]{10, 0, 20, 30, (byte) 255};
            byte[] largeObjectBytes = new byte[]{6, 7, 8, 9};
            Long preexistingOid = null;
            try {
                if (previousVersion > 0) {
                    flyway(isolatedUrl, schema, previousVersion).migrate();
                    if (previousVersion >= 12) {
                        try (Connection conn = DriverManager.getConnection(isolatedUrl, user, password);
                             var insert = conn.createStatement()) {
                            insert.execute("insert into mail_api_client(client_id,display_name) values ('existing','Existing client')");
                        }
                    }
                    // V1 predates tenant isolation and BYTEA; fixtures are inserted only once those
                    // historical columns exist, avoiding a synthetic schema that never shipped.
                    if (previousVersion >= 12) {
                        insertAttachment(isolatedUrl, legacyId, legacyBytes, null);
                        if (previousVersion == 16) {
                            try (Connection conn = DriverManager.getConnection(isolatedUrl, user, password);
                                 var s = conn.createStatement();
                                 var rs = s.executeQuery("select lo_from_bytea(0, decode('06070809','hex'))::bigint")) {
                                assertTrue(rs.next());
                                preexistingOid = rs.getLong(1);
                            }
                            insertAttachment(isolatedUrl, alreadyLargeObjectId, largeObjectBytes, preexistingOid);
                        }
                    }
                }
                Flyway current = flyway(isolatedUrl, schema, 0);
                current.migrate();
                assertTrue(current.validate().validationSuccessful, "Flyway validate failed for V" + previousVersion);
                try (Connection conn = DriverManager.getConnection(isolatedUrl, user, password);
                     var s = conn.createStatement()) {
                    try (var rs = s.executeQuery("select version from flyway_schema_history where success=true "
                            + "and version is not null order by installed_rank desc limit 1")) {
                        assertTrue(rs.next());
                        assertEquals("17", rs.getString(1));
                    }
                    try (var rs = s.executeQuery("select count(*) from information_schema.columns "
                            + "where table_schema=current_schema() and table_name='mail_attachment' and column_name='content'")) {
                        assertTrue(rs.next());
                        assertEquals(0, rs.getInt(1), "V17 must remove legacy BYTEA");
                    }
                    try (var rs = s.executeQuery("select count(*) from information_schema.columns "
                            + "where table_schema=current_schema() and table_name='mail_attachment' "
                            + "and column_name='content_oid' and is_nullable='NO'")) {
                        assertTrue(rs.next());
                        assertEquals(1, rs.getInt(1), "LO OID must be mandatory");
                    }
                    if (previousVersion >= 12) {
                        try (var rs = s.executeQuery("select permissions from mail_api_client where client_id='existing'")) {
                            assertTrue(rs.next());
                            assertEquals("*", rs.getString(1), "upgrade must preserve legacy client permissions");
                        }
                    }
                    s.execute("insert into mail_api_client(client_id,display_name) values ('new','New client')");
                    try (var rs = s.executeQuery("select permissions from mail_api_client where client_id='new'")) {
                        assertTrue(rs.next());
                        assertEquals("EMAIL_SEND,EMAIL_READ", rs.getString(1));
                    }
                }
                if (previousVersion >= 12) {
                    long migratedOid = assertAttachmentBytes(isolatedUrl, legacyId, legacyBytes);
                    if (previousVersion == 16) {
                        assertEquals(preexistingOid.longValue(),
                                assertAttachmentBytes(isolatedUrl, alreadyLargeObjectId, largeObjectBytes),
                                "V17 must preserve previously streamed Large Object OIDs");
                    }
                    // A DELETE must invoke the V16 trigger and unlink both migrated and original LOs.
                    try (Connection conn = DriverManager.getConnection(isolatedUrl, user, password);
                         var s = conn.createStatement()) {
                        s.executeUpdate("delete from mail_attachment where client_id='upgrade-tenant'");
                        try (var rs = s.executeQuery("select count(*) from pg_largeobject_metadata "
                                + "where oid=" + migratedOid)) {
                            assertTrue(rs.next());
                            assertEquals(0, rs.getInt(1), "converted LO leaked after metadata deletion");
                        }
                        if (preexistingOid != null) {
                            try (var rs = s.executeQuery("select count(*) from pg_largeobject_metadata "
                                    + "where oid=" + preexistingOid)) {
                                assertTrue(rs.next());
                                assertEquals(0, rs.getInt(1), "preexisting LO leaked after metadata deletion");
                            }
                        }
                    }
                }
            } finally {
                // The CI database is disposable; delete any remaining fixture LOs before DROP SCHEMA.
                try (Connection cleanup = DriverManager.getConnection(isolatedUrl, user, password);
                     var s = cleanup.createStatement()) {
                    try (var rs = s.executeQuery("select to_regclass('mail_attachment')")) {
                        if (rs.next() && rs.getString(1) != null) {
                            s.executeUpdate("delete from mail_attachment where client_id='upgrade-tenant'");
                        }
                    }
                } catch (Exception cleanupError) {
                    // Preserve the original failure; schema isolation prevents cross-test contamination.
                }
                stmt.execute("drop schema " + schema + " cascade");
            }
        }
    }

    private void insertAttachment(String isolatedUrl, UUID id, byte[] bytes, Long oid) throws Exception {
        String sql = """
                insert into mail_attachment(id,client_id,filename,content_type,size_bytes,
                    storage_key,checksum_sha256,created_at,content,content_oid)
                values (?,?,?,?,?,?,?,?,?,?::oid)
                """;
        if (oid == null) {
            sql = sql.replace(",content_oid)", ")").replace(",?::oid)", ")");
        }
        try (Connection conn = DriverManager.getConnection(isolatedUrl, user, password);
             var insert = conn.prepareStatement(sql)) {
            insert.setObject(1, id);
            insert.setString(2, "upgrade-tenant");
            insert.setString(3, "upgrade.bin");
            insert.setString(4, "application/octet-stream");
            insert.setLong(5, bytes.length);
            insert.setString(6, oid == null ? "legacy:" + id : "lo:" + oid);
            insert.setString(7, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
            insert.setTimestamp(8, java.sql.Timestamp.from(java.time.Instant.now()));
            if (oid == null) {
                insert.setBytes(9, bytes);
            } else {
                insert.setNull(9, java.sql.Types.BINARY);
                insert.setLong(10, oid);
            }
            assertEquals(1, insert.executeUpdate());
        }
    }

    private long assertAttachmentBytes(String isolatedUrl, UUID id, byte[] expected) throws Exception {
        try (Connection conn = DriverManager.getConnection(isolatedUrl, user, password);
             var select = conn.prepareStatement("select content_oid::bigint, lo_get(content_oid) "
                     + "from mail_attachment where id=?")) {
            select.setObject(1, id);
            try (var rs = select.executeQuery()) {
                assertTrue(rs.next(), "fixture disappeared during migration");
                long oid = rs.getLong(1);
                assertTrue(oid > 0);
                assertArrayEquals(expected, rs.getBytes(2), "migration changed attachment bytes");
                return oid;
            }
        }
    }

    private Flyway flyway(String jdbcUrl, String schema, int targetVersion) {
        var config = Flyway.configure().dataSource(jdbcUrl, user, password)
                .locations("classpath:db/migration").schemas(schema).defaultSchema(schema)
                .createSchemas(false);
        if (targetVersion > 0) config.target(MigrationVersion.fromVersion(Integer.toString(targetVersion)));
        return config.load();
    }
}
