package com.yeremitech.mailplatform.api.service;

import java.io.DataInputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ClamAvInstreamTest {
    @Test void sendsChunkedProtocolAndAcceptsCleanVerdict() throws Exception {
        assertVerdict("stream: OK\0", false);
    }

    @Test void rejectsInfectedVerdict() throws Exception {
        assertVerdict("stream: Eicar-Test-Signature FOUND\0", true);
    }

    @Test void rejectsStreamsThatChangeSizeBeforeAcceptingClamdVerdict() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> ClamAvInstream.scan(
                "127.0.0.1", 3310, new java.io.ByteArrayInputStream(new byte[]{1,2,3}),0,3000));
    }

    private void assertVerdict(String verdict, boolean infected) throws Exception {
        try (ServerSocket server = new ServerSocket(0); var pool = Executors.newSingleThreadExecutor()) {
            var future = pool.submit(() -> {
                try (var socket = server.accept()) {
                    socket.setSoTimeout(3000);
                    var in = new DataInputStream(socket.getInputStream());
                    byte[] command = in.readNBytes(10);
                    assertEquals("zINSTREAM\0", new String(command, StandardCharsets.US_ASCII));
                    int size = in.readInt();
                    assertEquals(4, size);
                    assertArrayEquals(new byte[]{1,2,3,4}, in.readNBytes(size));
                    assertEquals(0, in.readInt());
                    socket.getOutputStream().write(verdict.getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            if (infected) {
                assertThrows(ClamAvInstream.MalwareDetectedException.class, () ->
                        ClamAvInstream.scan("127.0.0.1", server.getLocalPort(), new byte[]{1,2,3,4}, 3000));
            } else {
                assertDoesNotThrow(() -> ClamAvInstream.scan("127.0.0.1", server.getLocalPort(),
                        new byte[]{1,2,3,4}, 3000));
            }
            future.get(3, TimeUnit.SECONDS);
            pool.shutdownNow();
        }
    }
}
