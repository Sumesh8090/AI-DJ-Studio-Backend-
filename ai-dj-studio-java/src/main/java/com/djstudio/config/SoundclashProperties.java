package com.djstudio.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Soundclash settings (app.soundclash.*). */
@ConfigurationProperties(prefix = "app.soundclash")
public record SoundclashProperties(long windowMs) {

    public SoundclashProperties {
        if (windowMs < 5_000 || windowMs > 300_000) {
            throw new IllegalStateException("app.soundclash.window-ms must be between 5000 and 300000.");
        }
    }
}
