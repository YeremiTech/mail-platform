package com.yeremitech.mailplatform.api.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** The admin bootstrap secret is separate from client keys; persistent clients cannot escalate. */
public final class ApiKeyFilter extends OncePerRequestFilter {
    private final Map<String, byte[]> legacySecrets;
    private final byte[] adminSecret;
    private final PersistentClientCredentials credentials;
    private final boolean legacyEnabled;

    public ApiKeyFilter(String configuredClients, String adminKey, boolean legacyEnabled,
                        PersistentClientCredentials credentials) {
        Map<String, byte[]> parsed = new LinkedHashMap<>();
        if (configuredClients != null && !configuredClients.isBlank()) {
            for (String entry : configuredClients.split(",")) {
                String[] parts = entry.trim().split("=", 2);
                if (parts.length != 2 || !parts[0].matches("[A-Za-z0-9._-]{1,120}")
                        || "admin".equalsIgnoreCase(parts[0])) throw new IllegalArgumentException("invalid INTERNAL_API_KEYS entry");
                byte[] secret = parts[1].getBytes(StandardCharsets.UTF_8);
                if (secret.length < 32 || parsed.putIfAbsent(parts[0], secret) != null) {
                    throw new IllegalArgumentException("every legacy key needs at least 32 bytes and a unique client id");
                }
            }
        }
        if (adminKey != null && !adminKey.isBlank() && adminKey.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("ADMIN_API_KEY must be at least 32 bytes");
        }
        this.legacySecrets = Map.copyOf(parsed);
        this.legacyEnabled = legacyEnabled;
        this.adminSecret = adminKey == null ? new byte[0] : adminKey.getBytes(StandardCharsets.UTF_8);
        this.credentials = credentials;
        if ((!legacyEnabled || parsed.isEmpty()) && !credentials.configured() && adminSecret.length == 0) {
            throw new IllegalArgumentException("configure API_KEY_PEPPER and persistent clients, legacy keys, or ADMIN_API_KEY");
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String client = request.getHeader("X-Client-Id");
        String raw = request.getHeader("X-Internal-Api-Key");
        boolean admin = "admin".equals(client) && adminSecret.length > 0 && compare(adminSecret, raw);
        boolean persistent = !admin && credentials.authenticate(client, raw);
        boolean legacy = !admin && !persistent && legacyEnabled && client != null
                && compare(legacySecrets.get(client), raw);
        if (admin || persistent || legacy) {
            if (persistent && !credentials.consumeRequestQuota(client)) {
                response.setStatus(429);
                response.setHeader("Retry-After", "60");
                response.setContentType("application/json");
                response.getWriter().write("{\"code\":\"RATE_LIMITED\",\"message\":\"Client request quota exceeded\"}");
                return;
            }
            List<SimpleGrantedAuthority> authorities = new ArrayList<>();
            authorities.add(new SimpleGrantedAuthority(admin ? "ROLE_ADMIN" : "ROLE_INTERNAL"));
            if (admin || legacy) {
                authorities.add(new SimpleGrantedAuthority("PERM_ALL"));
            } else {
                for (String permission : credentials.permissions(client)) {
                    authorities.add(new SimpleGrantedAuthority("*".equals(permission) ? "PERM_ALL" : "PERM_" + permission));
                }
            }
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                    client, null, authorities));
        }
        chain.doFilter(request, response);
    }

    private static boolean compare(byte[] expected, String raw) {
        return expected != null && raw != null && MessageDigest.isEqual(expected, raw.getBytes(StandardCharsets.UTF_8));
    }
}
