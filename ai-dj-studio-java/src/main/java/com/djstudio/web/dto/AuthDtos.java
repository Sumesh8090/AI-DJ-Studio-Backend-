package com.djstudio.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public final class AuthDtos {

    private AuthDtos() {
    }

    /** role may be omitted (defaults to listener). co_dj and admin can only be granted by an admin. */
    public record RegisterRequest(
            @NotBlank(message = "Email is required.")
            @Size(max = 254, message = "Email must be at most 254 characters.")
            @Email(message = "Enter a valid email address.") String email,
            @NotBlank(message = "Password is required.")
            @Size(min = 8, max = 128, message = "Password must be between 8 and 128 characters.") String password,
            @NotBlank(message = "displayName is required.")
            @Size(max = 40, message = "displayName must be at most 40 characters.") String displayName,
            @Pattern(regexp = "listener|host", message = "Role must be 'listener' or 'host'.") String role) {
    }

    public record LoginRequest(@NotBlank String email, @NotBlank String password) {
    }

    public record RefreshRequest(@NotBlank(message = "refreshToken is required.") String refreshToken) {
    }

    public record TokenResponse(String accessToken, String refreshToken, String tokenType, long expiresIn) {
    }

    public record UserResponse(String id, String email, String displayName, String role, Instant createdAt) {
    }

    public record RoleUpdateRequest(
            @NotBlank(message = "role is required.")
            @Pattern(regexp = "listener|co_dj|host|admin", message = "Role must be one of listener, co_dj, host, admin.")
            String role) {
    }
}
