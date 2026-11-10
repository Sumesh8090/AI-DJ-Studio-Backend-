package com.djstudio.repo;

import com.djstudio.domain.QueueEntry;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface QueueEntryRepository extends JpaRepository<QueueEntry, Long> {

    List<QueueEntry> findByLoungeIdOrderByPositionAsc(String loungeId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from QueueEntry q where q.loungeId = :loungeId")
    void deleteByLoungeId(@Param("loungeId") String loungeId);
}
