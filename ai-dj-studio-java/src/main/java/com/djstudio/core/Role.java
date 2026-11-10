package com.djstudio.core;

import java.util.Optional;

/** Four-tier RBAC: Listener &lt; Co-DJ &lt; Host &lt; Admin (PRD 10.1). */
public enum Role {
    LISTENER("listener", 1),
    CO_DJ("co_dj", 2),
    HOST("host", 3),
    ADMIN("admin", 4);

    private final String value;
    private final int rank;

    Role(String value, int rank) {
        this.value = value;
        this.rank = rank;
    }

    /** The value stored in the database and carried in API payloads. */
    public String value() {
        return value;
    }

    public int rank() {
        return rank;
    }

    public static Optional<Role> fromValue(String value) {
        if (value == null) {
            return Optional.empty();
        }
        for (Role r : values()) {
            if (r.value.equals(value)) {
                return Optional.of(r);
            }
        }
        return Optional.empty();
    }
}
