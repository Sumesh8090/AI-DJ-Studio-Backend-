package com.djstudio.security;

import com.djstudio.core.Permission;
import com.djstudio.web.ApiException;

/** Server-side RBAC check used by every role-restricted endpoint. Hiding a button in a UI is never enough. */
public final class AccessGuard {

    private AccessGuard() {
    }

    public static void require(UserPrincipal user, Permission permission) {
        if (!permission.allows(user.role())) {
            throw new ApiException(403, "forbidden",
                    "Your role '" + user.role() + "' is not allowed to perform this action ("
                            + permission.name().toLowerCase() + ").");
        }
    }
}
