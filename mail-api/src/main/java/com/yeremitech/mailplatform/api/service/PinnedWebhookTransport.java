package com.yeremitech.mailplatform.api.service;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/** TLS hostname verification with an IP-pinned TCP socket: no second DNS lookup, proxy or redirects. */
final class PinnedWebhookTransport {
    private static final int CONNECT_TIMEOUT_MS = (int) Duration.ofSeconds(4).toMillis();
    private static final int READ_TIMEOUT_MS = (int) Duration.ofSeconds(8).toMillis();

    int post(URI endpoint, byte[] body, String eventId, String timestamp, String signature) throws IOException {
        if (!"https".equalsIgnoreCase(endpoint.getScheme()) || endpoint.getHost() == null
                || endpoint.getRawUserInfo() != null || endpoint.getRawFragment() != null
                || (endpoint.getPort() != -1 && endpoint.getPort() != 443)) {
            throw new IllegalArgumentException("webhook endpoint must be an approved HTTPS URI");
        }
        // Perform DNS validation immediately before connecting. Connect to the verified IP,
        // but preserve the original hostname for SNI and HTTPS certificate verification.
        InetAddress pinned = WebhookAddressPolicy.resolvePublicAddress(endpoint.getHost());
        return postPinned(endpoint, pinned, body, eventId, timestamp, signature);
    }

    int postPinned(URI endpoint, InetAddress pinned, byte[] body,
                   String eventId, String timestamp, String signature) throws IOException {
        if (!WebhookAddressPolicy.isPublic(pinned))
            throw new IllegalArgumentException("webhook destination must be a public IP address");
        String host = endpoint.getHost();
        if (!"https".equalsIgnoreCase(endpoint.getScheme()) || host == null
                || endpoint.getRawUserInfo() != null || endpoint.getRawFragment() != null
                || (endpoint.getPort() != -1 && endpoint.getPort() != 443)) {
            throw new IllegalArgumentException("webhook endpoint must be HTTPS on port 443");
        }
        int port = 443;
        String target = endpoint.getRawPath();
        if (target == null || target.isEmpty()) target = "/";
        if (endpoint.getRawQuery() != null) target += "?" + endpoint.getRawQuery();
        try (Socket tcp = new Socket()) {
            tcp.connect(new InetSocketAddress(pinned, port), CONNECT_TIMEOUT_MS);
            tcp.setSoTimeout(READ_TIMEOUT_MS);
            try (SSLSocket tls = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                    .createSocket(tcp, host, port, true)) {
                tls.setSoTimeout(READ_TIMEOUT_MS);
                SSLParameters parameters = tls.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                parameters.setApplicationProtocols(new String[]{"http/1.1"});
                if (host.indexOf(':') < 0 && !host.matches("[0-9.]+")) {
                    parameters.setServerNames(List.of(new SNIHostName(host)));
                }
                tls.setSSLParameters(parameters);
                tls.startHandshake();
                String headers = "POST " + target + " HTTP/1.1\r\n"
                        + "Host: " + endpoint.getRawAuthority() + "\r\n"
                        + "Content-Type: application/json\r\n"
                        + "Content-Length: " + body.length + "\r\n"
                        + "Connection: close\r\n"
                        + "X-Mail-Event-Id: " + safeHeader(eventId) + "\r\n"
                        + "X-Mail-Timestamp: " + safeHeader(timestamp) + "\r\n"
                        + "X-Mail-Signature: sha256=" + safeHeader(signature) + "\r\n\r\n";
                tls.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
                tls.getOutputStream().write(body);
                tls.getOutputStream().flush();
                String status = readLine(tls.getInputStream());
                if (!status.matches("HTTP/1\\.[01] [0-9]{3}( .*)?"))
                    throw new IOException("webhook receiver returned an invalid HTTP status");
                return Integer.parseInt(status.substring(9, 12));
            }
        }
    }

    private static String safeHeader(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_=.:-]{1,160}"))
            throw new IllegalArgumentException("invalid webhook signature header");
        return value;
    }

    private static String readLine(InputStream input) throws IOException {
        byte[] bytes = new byte[1024];
        int n = 0;
        while (n < bytes.length) {
            int b = input.read();
            if (b < 0) throw new IOException("webhook receiver closed the connection");
            if (b == '\n') {
                if (n > 0 && bytes[n - 1] == '\r') n--;
                return new String(bytes, 0, n, StandardCharsets.US_ASCII);
            }
            bytes[n++] = (byte) b;
        }
        throw new IOException("webhook receiver response header too long");
    }
}
