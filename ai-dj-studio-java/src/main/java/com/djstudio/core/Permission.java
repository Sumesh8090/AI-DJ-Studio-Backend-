package com.djstudio.core;

/**
 * Actions guarded by RBAC, each with the minimum role required (a higher role also holds it).
 * Enforced server-side by {@code AccessGuard} for HTTP and, from Week 2, by the WebSocket gateway
 * using the same {@link #allows(String)} rule, so the policy lives in exactly one place.
 * Unknown roles are denied.
 */
public enum Permission {
    JOIN_LOUNGE(Role.LISTENER),
    BROWSE_TRACKS(Role.LISTENER),
    CREATE_LOUNGE(Role.HOST),
    MANAGE_LOUNGE(Role.HOST),      // start/end session, lock, mute/remove participants, transfer roles
    OVERRIDE_QUEUE(Role.CO_DJ),    // add/remove/promote/advance, approve or reject an AI segue
    RESOLVE_BATTLE(Role.CO_DJ),    // Week 3: force-resolve a Soundclash
    MANAGE_TRACKS(Role.ADMIN),     // upload / edit acoustic profiles
    MANAGE_USERS(Role.ADMIN);      // change roles

    private final Role minimum;

    Permission(Role minimum) {
        this.minimum = minimum;
    }

    public Role minimumRole() {
        return minimum;
    }

    public boolean allows(String roleValue) {
        return Role.fromValue(roleValue).map(r -> r.rank() >= minimum.rank()).orElse(false);
    }
}
