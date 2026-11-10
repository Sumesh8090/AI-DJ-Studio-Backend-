package com.djstudio.config;

import java.util.Arrays;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** WebSocket gateway settings (app.ws.*). Bound by @ConfigurationPropertiesScan. */
@ConfigurationProperties(prefix = "app.ws")
public record WsProperties(String allowedOrigins, long presenceGraceMs, int sendTimeLimitMs, int sendBufferBytes,
                           int maxMessageBytes) {

    public WsProperties {
        if (presenceGraceMs < 0 || sendTimeLimitMs < 1 || sendBufferBytes < 1024 || maxMessageBytes < 256) {
            throw new IllegalStateException("Invalid app.ws.* settings.");
        }
    }

    /** Parsed origin list; never contains "*" (a wildcard would defeat the browser Origin check). */
    public List<String> origins() {
        if (allowedOrigins == null || allowedOrigins.isBlank()) {
            return List.of();
        }
        List<String> out = Arrays.stream(allowedOrigins.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        if (out.contains("*")) {
            throw new IllegalStateException("app.ws.allowed-origins must list explicit origins, not '*'.");
        }
        return out;
    }
}
