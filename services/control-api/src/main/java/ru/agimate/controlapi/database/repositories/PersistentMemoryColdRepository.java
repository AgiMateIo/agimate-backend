package ru.agimate.controlapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.agimate.controlapi.database.entities.PersistentMemoryCold;

import java.util.Optional;
import java.util.UUID;

public interface PersistentMemoryColdRepository extends JpaRepository<PersistentMemoryCold, UUID> {

    Optional<PersistentMemoryCold> findByScopeId(UUID scopeId);

    /**
     * Compare-and-swap of the cold record: rewrites content and increments version only when the
     * current version matches the expected one. {@code 0} rows → a conflict (memory was changed by a
     * concurrent consolidation), and the caller re-reads and retries.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE PersistentMemoryCold c
            SET c.content = :content, c.version = c.version + 1
            WHERE c.scopeId = :scopeId AND c.version = :expectedVersion
            """)
    int casUpdate(@Param("scopeId") UUID scopeId,
                  @Param("content") String content,
                  @Param("expectedVersion") int expectedVersion);
}
