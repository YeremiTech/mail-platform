package com.yeremitech.mailplatform.api.security;

import java.util.HashMap;
import java.util.Map;

public final class ClientDisplayNames {
    private final Map<String, String> names;

    public ClientDisplayNames(String configuration) {
        Map<String, String> parsed = new HashMap<>();
        if (configuration != null && !configuration.isBlank()) {
            for (String entry : configuration.split(",")) {
                String[] parts = entry.trim().split("=", 2);
                if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()
                        || parts[1].length() > 100 || parts[1].indexOf('\r') >= 0 || parts[1].indexOf('\n') >= 0
                        || parsed.putIfAbsent(parts[0], parts[1].trim()) != null) {
                    throw new IllegalArgumentException("invalid CLIENT_DISPLAY_NAMES entry");
                }
            }
        }
        names = Map.copyOf(parsed);
    }

    public String nameFor(String clientId) { return names.getOrDefault(clientId, clientId); }
}
