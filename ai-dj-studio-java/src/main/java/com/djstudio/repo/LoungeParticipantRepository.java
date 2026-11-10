package com.djstudio.repo;

import com.djstudio.domain.LoungeParticipant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LoungeParticipantRepository extends JpaRepository<LoungeParticipant, Long> {

    Optional<LoungeParticipant> findByLoungeIdAndUserId(String loungeId, String userId);

    long countByLoungeIdAndLeftAtIsNull(String loungeId);

    boolean existsByLoungeIdAndUserIdAndLeftAtIsNull(String loungeId, String userId);

    java.util.List<LoungeParticipant> findByLoungeIdAndLeftAtIsNull(String loungeId);
}
