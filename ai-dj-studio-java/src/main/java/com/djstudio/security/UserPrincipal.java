package com.djstudio.security;

/** The authenticated caller. The role comes from the database on every request, not from the token. */
public record UserPrincipal(String id, String email, String role) {
}
