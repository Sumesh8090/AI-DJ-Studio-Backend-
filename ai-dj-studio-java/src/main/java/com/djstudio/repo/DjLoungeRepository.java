package com.djstudio.repo;

import com.djstudio.domain.DjLounge;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DjLoungeRepository extends JpaRepository<DjLounge, String> {

    boolean existsByInviteCode(String inviteCode);

    /**
     * Row-locks the lounge (SELECT ... FOR UPDATE) so concurrent joins are serialised and the capacity check
     * cannot be raced past. Must be called inside a transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from DjLounge l where l.inviteCode = :code")
    Optional<DjLounge> findByInviteCodeForUpdate(@Param("code") String code);
}
