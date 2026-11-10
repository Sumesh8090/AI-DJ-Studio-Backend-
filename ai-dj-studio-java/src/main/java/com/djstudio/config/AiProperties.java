package com.djstudio.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** AI DJ settings (app.ai.*). */
@ConfigurationProperties(prefix = "app.ai")
public record AiProperties(boolean enabled, int outroWindowSeconds, long autoApproveMs) {

    public AiProperties {
        if (outroWindowSeconds < 1 || autoApproveMs < 1000) {
            throw new IllegalStateException("Invalid app.ai.* settings.");
        }
    }
}
