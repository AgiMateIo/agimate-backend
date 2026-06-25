package ru.agimate.controlapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.agimate.controlapi.database.entities.PersistentMemoryHot;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface PersistentMemoryHotRepository extends JpaRepository<PersistentMemoryHot, UUID> {

    /** Все заметки scope (сконсолидированные удаляются, поэтому всё в hot — ещё «pending»). */
    List<PersistentMemoryHot> findByScopeIdOrderByCreatedAtAsc(UUID scopeId);

    /** Заметки конкретной партии консолидации — для доставки в триггер. */
    List<PersistentMemoryHot> findByConsolidationIdOrderByCreatedAtAsc(UUID consolidationId);

    /**
     * Single-flight: есть ли у scope незавершённая консолидация (заклеймленные заметки,
     * чей лиз ещё жив). {@code claimedAt >= leaseThreshold} — клейм не протух.
     */
    @Query("""
            SELECT COUNT(h) FROM PersistentMemoryHot h
            WHERE h.scopeId = :scopeId AND h.consolidationId IS NOT NULL AND h.claimedAt >= :leaseThreshold
            """)
    long countInFlight(@Param("scopeId") UUID scopeId, @Param("leaseThreshold") LocalDateTime leaseThreshold);

    /**
     * Клеймит под партию {@code consolidationId} все ещё-несконсолидированные заметки scope,
     * включая реклейм брошенных (клейм протух: {@code claimedAt < leaseThreshold}).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE PersistentMemoryHot h
            SET h.consolidationId = :consolidationId, h.claimedAt = :now
            WHERE h.scopeId = :scopeId
              AND (h.consolidationId IS NULL OR h.claimedAt < :leaseThreshold)
            """)
    int claim(@Param("scopeId") UUID scopeId,
              @Param("consolidationId") UUID consolidationId,
              @Param("now") LocalDateTime now,
              @Param("leaseThreshold") LocalDateTime leaseThreshold);

    /** Удаляет заметки сконсолидированной партии (вызывается в одной tx с записью cold). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM PersistentMemoryHot h WHERE h.consolidationId = :consolidationId")
    int deleteByConsolidationId(@Param("consolidationId") UUID consolidationId);
}
