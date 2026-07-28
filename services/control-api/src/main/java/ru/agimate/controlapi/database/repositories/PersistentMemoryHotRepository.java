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

    /** Every note of a scope (consolidated ones are deleted, so everything in hot is still «pending»). */
    List<PersistentMemoryHot> findByScopeIdOrderByCreatedAtAsc(UUID scopeId);

    /** Notes of a particular consolidation batch — for delivery into the trigger. */
    List<PersistentMemoryHot> findByConsolidationIdOrderByCreatedAtAsc(UUID consolidationId);

    /**
     * Single-flight: whether the scope has an unfinished consolidation (claimed notes whose lease is
     * still alive). {@code claimedAt >= leaseThreshold} means the claim has not expired.
     */
    @Query("""
            SELECT COUNT(h) FROM PersistentMemoryHot h
            WHERE h.scopeId = :scopeId AND h.consolidationId IS NOT NULL AND h.claimedAt >= :leaseThreshold
            """)
    long countInFlight(@Param("scopeId") UUID scopeId, @Param("leaseThreshold") LocalDateTime leaseThreshold);

    /**
     * Claims, under the batch {@code consolidationId}, every not-yet-consolidated note of the scope,
     * including reclaiming abandoned ones (an expired claim: {@code claimedAt < leaseThreshold}).
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

    /** Deletes the notes of a consolidated batch (called in the same tx as the cold write). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM PersistentMemoryHot h WHERE h.consolidationId = :consolidationId")
    int deleteByConsolidationId(@Param("consolidationId") UUID consolidationId);
}
