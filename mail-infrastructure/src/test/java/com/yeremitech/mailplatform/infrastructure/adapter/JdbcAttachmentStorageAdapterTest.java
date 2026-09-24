package com.yeremitech.mailplatform.infrastructure.adapter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.postgresql.PGConnection;
import org.postgresql.largeobject.LargeObject;
import org.postgresql.largeobject.LargeObjectManager;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class JdbcAttachmentStorageAdapterTest {

    @Test
    void storesAttachmentIncrementallyInPostgresLargeObject() throws Exception {
        byte[] content = "streamed-content".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Fixture f = new Fixture();
        when(f.manager.createLO(anyInt())).thenReturn(42L);
        when(f.manager.open(42L, LargeObjectManager.WRITE)).thenReturn(f.largeObject);

        var stored = f.adapter.store("inventory", "invoice.pdf", "application/pdf",
                new ByteArrayInputStream(content), content.length);

        assertEquals("inventory", stored.clientId());
        assertEquals("lo:42", stored.storageKey());
        assertEquals(content.length, stored.sizeBytes());
        verify(f.largeObject).write(any(byte[].class), eq(0), eq(content.length));
        verify(f.statement).executeUpdate();
        verify(f.connection).commit();
        verify(f.connection, never()).rollback();
    }

    @Test
    void rollsBackWhenDeclaredSizeDoesNotMatchStream() throws Exception {
        Fixture f = new Fixture();
        when(f.manager.createLO(anyInt())).thenReturn(84L);
        when(f.manager.open(84L, LargeObjectManager.WRITE)).thenReturn(f.largeObject);

        assertThrows(IllegalArgumentException.class, () -> f.adapter.store(
                "inventory", "x.pdf", "application/pdf", new ByteArrayInputStream(new byte[]{1,2,3}), 2));

        verify(f.connection).rollback();
        verify(f.statement, never()).executeUpdate();
    }

    @Test
    void rejectsInvalidSizeBeforeOpeningDatabaseConnection() {
        DataSource ds = mock(DataSource.class);
        var adapter = new JdbcAttachmentStorageAdapter(new NamedParameterJdbcTemplate(ds));
        assertThrows(IllegalArgumentException.class, () -> adapter.store(
                "inventory", "x.pdf", "application/pdf", InputStream.nullInputStream(), 0));
        assertDoesNotThrow(() -> verify(ds, never()).getConnection());
    }

    @Test
    void loadsLargeObjectAsStreamAndReleasesTransactionWhenClosed() throws Exception {
        Fixture f = new Fixture();
        ResultSet rs = mock(ResultSet.class);
        when(f.statement.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        UUID id = UUID.randomUUID();
        when(rs.getObject("id", UUID.class)).thenReturn(id);
        when(rs.getString("client_id")).thenReturn("inventory");
        when(rs.getString("filename")).thenReturn("invoice.pdf");
        when(rs.getString("content_type")).thenReturn("application/pdf");
        when(rs.getLong("size_bytes")).thenReturn(3L);
        when(rs.getString("storage_key")).thenReturn("lo:91");
        when(rs.getString("checksum_sha256")).thenReturn("00");
        when(rs.getLong("content_oid")).thenReturn(91L);
        when(rs.wasNull()).thenReturn(false);
        when(f.manager.open(91L, LargeObjectManager.READ)).thenReturn(f.largeObject);
        when(f.largeObject.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[]{1,2,3}));

        var stored = f.adapter.load("inventory", id).orElseThrow();
        assertArrayEquals(new byte[]{1,2,3}, stored.content().readAllBytes());
        stored.content().close();

        verify(f.largeObject).close();
        verify(f.connection).commit();
        verify(f.connection).close();
    }

    @Test
    void refusesRowsThatWereNotMigratedToLargeObjects() throws Exception {
        Fixture f = new Fixture();
        ResultSet rs = mock(ResultSet.class);
        when(f.statement.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getObject("id", UUID.class)).thenReturn(UUID.randomUUID());
        when(rs.getString("client_id")).thenReturn("inventory");
        when(rs.getString("filename")).thenReturn("legacy.pdf");
        when(rs.getString("content_type")).thenReturn("application/pdf");
        when(rs.getLong("size_bytes")).thenReturn(3L);
        when(rs.getString("storage_key")).thenReturn("legacy");
        when(rs.getString("checksum_sha256")).thenReturn("00");
        when(rs.getLong("content_oid")).thenReturn(0L);
        when(rs.wasNull()).thenReturn(true);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> f.adapter.load("inventory", UUID.randomUUID()));
        assertTrue(ex.getMessage().contains("unable to load attachment"));
        verify(f.connection).rollback();
        verify(f.connection).close();
    }

    private static final class Fixture {
        final DataSource dataSource = mock(DataSource.class);
        final Connection connection = mock(Connection.class);
        final PGConnection pg = mock(PGConnection.class);
        final LargeObjectManager manager = mock(LargeObjectManager.class);
        final LargeObject largeObject = mock(LargeObject.class);
        final PreparedStatement statement = mock(PreparedStatement.class);
        final JdbcAttachmentStorageAdapter adapter;

        Fixture() throws Exception {
            when(dataSource.getConnection()).thenReturn(connection);
            when(connection.unwrap(PGConnection.class)).thenReturn(pg);
            when(pg.getLargeObjectAPI()).thenReturn(manager);
            when(connection.prepareStatement(anyString())).thenReturn(statement);
            adapter = new JdbcAttachmentStorageAdapter(new NamedParameterJdbcTemplate(dataSource));
        }
    }
}
