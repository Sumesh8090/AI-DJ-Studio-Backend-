package com.djstudio.repo;

import com.djstudio.domain.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, String> {

    /** Reuse of a rotated token is treated as theft: revoke the user's whole token family. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update RefreshToken r set r.revoked = true where r.userId = :userId")
    int revokeAllForUser(@Param("userId") String userId);
}
