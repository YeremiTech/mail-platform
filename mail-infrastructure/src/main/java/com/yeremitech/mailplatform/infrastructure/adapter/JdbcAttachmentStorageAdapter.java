package com.yeremitech.mailplatform.infrastructure.adapter;

import com.yeremitech.mailplatform.application.port.AttachmentStoragePort;
import com.yeremitech.mailplatform.domain.AttachmentRef;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.postgresql.PGConnection;
import org.postgresql.largeobject.LargeObject;
import org.postgresql.largeobject.LargeObjectManager;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Multi-instance PostgreSQL large-object storage: bounded upload and download, tenant-checked reads. */
public final class JdbcAttachmentStorageAdapter implements AttachmentStoragePort {
    private static final int MAX_BYTES = 15 * 1024 * 1024;
    private final NamedParameterJdbcTemplate jdbc;
    private final DataSource dataSource;

    public JdbcAttachmentStorageAdapter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.dataSource = Objects.requireNonNull(jdbc.getJdbcTemplate().getDataSource());
    }

    @Override
    public AttachmentRef store(String clientId, String filename, String type, InputStream input, long declaredSize) {
        if (declaredSize < 1 || declaredSize > MAX_BYTES)
            throw new IllegalArgumentException("attachment exceeds 15 MB");
        String safe = Objects.requireNonNull(filename).replace('\\', '/');
        safe = safe.substring(safe.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "");
        if (safe.isBlank() || safe.length() > 255) throw new IllegalArgumentException("invalid attachment filename");
        UUID id = UUID.randomUUID();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false); // PostgreSQL large objects require an open transaction.
            try {
                LargeObjectManager manager = connection.unwrap(PGConnection.class).getLargeObjectAPI();
                long oid = manager.createLO(LargeObjectManager.READ | LargeObjectManager.WRITE);
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                long copied = 0;
                try (LargeObject object = manager.open(oid, LargeObjectManager.WRITE)) {
                    byte[] buffer = new byte[32 * 1024];
                    int n;
                    while ((n = input.read(buffer)) != -1) {
                        copied += n;
                        if (copied > declaredSize) throw new IllegalArgumentException("attachment size is invalid");
                        object.write(buffer, 0, n);
                        digest.update(buffer, 0, n);
                    }
                }
                if (copied != declaredSize) throw new IllegalArgumentException("attachment size is invalid");
                String checksum = HexFormat.of().formatHex(digest.digest());
                try (PreparedStatement stmt = connection.prepareStatement("""
                        insert into mail_attachment(id, client_id, filename, content_type, size_bytes,
                            storage_key, checksum_sha256, created_at, content_oid)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?::oid)
                        """)) {
                    stmt.setObject(1, id);
                    stmt.setString(2, clientId);
                    stmt.setString(3, safe);
                    stmt.setString(4, type);
                    stmt.setLong(5, copied);
                    stmt.setString(6, "lo:" + oid);
                    stmt.setString(7, checksum);
                    stmt.setTimestamp(8, Timestamp.from(Instant.now()));
                    stmt.setLong(9, oid);
                    stmt.executeUpdate();
                }
                connection.commit();
                return new AttachmentRef(id, clientId, safe, type, copied, "lo:" + oid, checksum);
            } catch (Exception ex) {
                try { connection.rollback(); } catch (SQLException failed) { ex.addSuppressed(failed); }
                if (ex instanceof IllegalArgumentException invalid) throw invalid;
                throw new IllegalStateException("unable to store attachment", ex);
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("unable to open attachment storage", ex);
        }
    }

    @Override
    public Optional<StoredAttachment> load(String clientId, UUID id) {
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            connection.setAutoCommit(false);
            try (PreparedStatement stmt = connection.prepareStatement("""
                    select id, client_id, filename, content_type, size_bytes, storage_key,
                           checksum_sha256, content_oid
                    from mail_attachment where id = ? and client_id = ? for share
                    """)) {
                stmt.setObject(1, id);
                stmt.setString(2, clientId);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (!rs.next()) {
                        connection.rollback(); connection.close();
                        return Optional.empty();
                    }
                    AttachmentRef metadata = new AttachmentRef(rs.getObject("id", UUID.class),
                            rs.getString("client_id"), rs.getString("filename"),
                            rs.getString("content_type"), rs.getLong("size_bytes"),
                            rs.getString("storage_key"), rs.getString("checksum_sha256"));
                    long oid = rs.getLong("content_oid");
                    if (rs.wasNull()) {
                        throw new IllegalStateException("attachment has not been migrated to large-object storage");
                    }
                    LargeObject largeObject = connection.unwrap(PGConnection.class)
                            .getLargeObjectAPI().open(oid, LargeObjectManager.READ);
                    Connection heldConnection = connection;
                    connection = null; // stream owns both the LO and its locked transaction until close().
                    InputStream stream = new FilterInputStream(largeObject.getInputStream()) {
                        private boolean closed;
                        @Override public void close() throws IOException {
                            if (closed) return;
                            closed = true;
                            try {
                                super.close();
                                largeObject.close();
                                heldConnection.commit();
                            } catch (SQLException ex) {
                                try { heldConnection.rollback(); } catch (SQLException rollback) { ex.addSuppressed(rollback); }
                                throw new IOException("cannot close attachment stream", ex);
                            } finally {
                                try { heldConnection.close(); } catch (SQLException ex) { throw new IOException(ex); }
                            }
                        }
                    };
                    return Optional.of(new StoredAttachment(metadata, stream));
                }
            }
        } catch (Exception ex) {
            if (connection != null) {
                try { connection.rollback(); connection.close(); } catch (SQLException error) { ex.addSuppressed(error); }
            }
            throw new IllegalStateException("unable to load attachment", ex);
        }
    }

    @Override
    public boolean belongsTo(String clientId, UUID attachmentId) {
        Integer count = jdbc.queryForObject("select count(*) from mail_attachment where id=:id and client_id=:clientId",
                Map.of("id", attachmentId, "clientId", clientId), Integer.class);
        return count != null && count > 0;
    }
}
