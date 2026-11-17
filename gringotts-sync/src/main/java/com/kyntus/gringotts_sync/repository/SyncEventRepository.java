package com.kyntus.gringotts_sync.repository;

import com.kyntus.gringotts_sync.domain.SyncEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

public interface SyncEventRepository extends JpaRepository<SyncEvent, Long> {

    @Query("SELECT e FROM SyncEvent e WHERE " +
            "(:source = '' OR e.source = :source) AND " +
            "(:level = '' OR e.level = :level) AND " +
            "(:period = '' OR e.period = :period) AND " +
            "(:beforeId = 0 OR e.id < :beforeId) AND " +
            "(:search = '' OR LOWER(e.message) LIKE LOWER(CONCAT('%', :search, '%'))) " +
            "ORDER BY e.id DESC")
    List<SyncEvent> search(@Param("source") String source,
                           @Param("level") String level,
                           @Param("period") String period,
                           @Param("beforeId") long beforeId,
                           @Param("search") String search,
                           Pageable pageable);

    @Query("SELECT e.level, COUNT(e) FROM SyncEvent e WHERE e.createdAt >= :since GROUP BY e.level")
    List<Object[]> countByLevelSince(@Param("since") LocalDateTime since);

    @Modifying
    @Transactional
    @Query("DELETE FROM SyncEvent e WHERE e.createdAt < :before")
    int deleteOlderThan(@Param("before") LocalDateTime before);
}
