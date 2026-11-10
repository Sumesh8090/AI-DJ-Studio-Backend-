package com.djstudio;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

// UserDetailsServiceAutoConfiguration is excluded: authentication is JWT-only, so Spring Boot's
// generated in-memory user (and its random password in the logs) must not exist.
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
public class DjStudioApplication {

    public static void main(String[] args) {
        SpringApplication.run(DjStudioApplication.class, args);
    }
}
