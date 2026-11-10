package com.djstudio.repo;

import com.djstudio.domain.Track;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TrackRepository extends JpaRepository<Track, String> {

    @Query("""
            select t from Track t
            where (:bpmMin is null or t.bpm >= :bpmMin)
              and (:bpmMax is null or t.bpm <= :bpmMax)
              and (:key is null or t.camelotKey = :key)
            """)
    List<Track> search(@Param("bpmMin") Double bpmMin, @Param("bpmMax") Double bpmMax,
                       @Param("key") String key, Pageable pageable);

    /** Narrow segue candidates with the indexed BPM column (direct, half-time and double-time windows). */
    @Query("""
            select t from Track t
            where t.id <> :excludeId
              and ((t.bpm between :lo1 and :hi1) or (t.bpm between :lo2 and :hi2) or (t.bpm between :lo3 and :hi3))
            """)
    List<Track> findBpmCandidates(@Param("excludeId") String excludeId,
                                  @Param("lo1") double lo1, @Param("hi1") double hi1,
                                  @Param("lo2") double lo2, @Param("hi2") double hi2,
                                  @Param("lo3") double lo3, @Param("hi3") double hi3);
}
