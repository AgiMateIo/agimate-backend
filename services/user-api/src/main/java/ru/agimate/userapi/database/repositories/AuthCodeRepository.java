package ru.agimate.userapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.agimate.userapi.database.entities.AuthCode;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AuthCodeRepository extends JpaRepository<AuthCode, UUID> {

    Optional<AuthCode> findByCodeHash(String codeHash);

    /**
     * Spends the code, and does it as the condition of the write rather than after a read: two
     * exchanges arriving together must produce one set of tokens, not two.
     *
     * @return 1 if this caller spent it, 0 if it was already spent
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE AuthCode c
            SET c.usedAt = :now, c.updatedAt = :now
            WHERE c.codeHash = :codeHash AND c.usedAt IS NULL
            """)
    int claim(@Param("codeHash") String codeHash, @Param("now") LocalDateTime now);

    /** Written after the session exists, which is why it is a second statement and not part of {@link #claim}. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE AuthCode c
            SET c.sessionId = :sessionId, c.updatedAt = :now
            WHERE c.id = :id
            """)
    int attachSession(@Param("id") UUID id,
                      @Param("sessionId") UUID sessionId,
                      @Param("now") LocalDateTime now);

    /**
     * Spent and expired codes alike are dropped only well after they died: a code replayed while
     * its row still exists reveals a stolen redirect, and deleting the row promptly would turn that
     * signal into an ordinary "unknown code".
     */
    @Modifying
    @Query("DELETE FROM AuthCode c WHERE c.expiresAt < :cutoff")
    int deleteExpired(@Param("cutoff") LocalDateTime cutoff);
}
