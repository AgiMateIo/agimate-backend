package ru.agimate.userapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.agimate.userapi.database.entities.ProviderLinkProof;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProviderLinkProofRepository extends JpaRepository<ProviderLinkProof, UUID> {

    Optional<ProviderLinkProof> findByProofHash(String proofHash);

    /**
     * Spends the proof as the condition of the write, like every other one-time secret here: two
     * requests carrying one proof must bind a provider once, not twice.
     *
     * @return 1 if this caller spent it, 0 if it was already spent
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE ProviderLinkProof p
            SET p.usedAt = :now, p.updatedAt = :now
            WHERE p.proofHash = :proofHash AND p.usedAt IS NULL
            """)
    int claim(@Param("proofHash") String proofHash, @Param("now") LocalDateTime now);

    @Modifying
    @Query("DELETE FROM ProviderLinkProof p WHERE p.expiresAt < :cutoff")
    int deleteExpired(@Param("cutoff") LocalDateTime cutoff);
}
