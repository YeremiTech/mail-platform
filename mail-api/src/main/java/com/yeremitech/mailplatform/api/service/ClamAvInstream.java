package com.yeremitech.mailplatform.api.service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** Streams bounded uploads to clamd and rejects missing, ambiguous or unavailable verdicts. */
public final class ClamAvInstream {
    public static final long MAX_FILE_SIZE = 15L * 1024 * 1024;
    private static final int MAX_RESULT_BYTES = 512;
    private ClamAvInstream() {}

    /** Compatibility overload for existing tests and internal callers. */
    public static void scan(String host, int port, byte[] data, int timeoutMs) throws IOException {
        if (data == null) throw new IllegalArgumentException("attachment must not be null");
        scan(host, port, new ByteArrayInputStream(data), data.length, timeoutMs);
    }

    public static void scan(String host, int port, InputStream data, long declaredSize, int timeoutMs)
            throws IOException {
        if (host == null || host.isBlank() || port < 1 || port > 65535 || timeoutMs < 1000
                || data == null || declaredSize < 1 || declaredSize > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("invalid antivirus scanner configuration or attachment");
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            output.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
            byte[] chunk = new byte[8192];
            long processed = 0;
            int size;
            while ((size = data.read(chunk)) != -1) {
                if (size == 0) continue;
                processed += size;
                if (processed > declaredSize || processed > MAX_FILE_SIZE) {
                    throw new IllegalArgumentException("attachment stream exceeded declared size");
                }
                output.writeInt(size);
                output.write(chunk, 0, size);
            }
            if (processed != declaredSize) {
                throw new IllegalArgumentException("attachment stream size changed during scanning");
            }
            output.writeInt(0);
            output.flush();
            ByteArrayOutputStream reply = new ByteArrayOutputStream();
            int value;
            while ((value = socket.getInputStream().read()) != -1 && value != '\0' && value != '\n') {
                if (reply.size() >= MAX_RESULT_BYTES) throw new IOException("scanner response too long");
                reply.write(value);
            }
            if (reply.size() == 0) throw new IOException("scanner returned no verdict");
            String result = reply.toString(StandardCharsets.US_ASCII);
            if (result.endsWith(" OK")) return;
            if (result.endsWith(" FOUND")) throw new MalwareDetectedException();
            throw new IOException("scanner did not return a clean verdict");
        }
    }

    public static final class MalwareDetectedException extends RuntimeException {
        public MalwareDetectedException() { super("attachment was rejected by antivirus scanner"); }
    }
}
