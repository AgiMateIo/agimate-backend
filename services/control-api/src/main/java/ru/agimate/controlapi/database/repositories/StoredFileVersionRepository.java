package ru.agimate.controlapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.agimate.controlapi.database.entities.StoredFileVersion;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface StoredFileVersionRepository extends JpaRepository<StoredFileVersion, UUID> {

    Optional<StoredFileVersion> findByFileIdAndVersion(UUID fileId, Integer version);

    /**
     * Bytes a user wrote since {@code since} — the daily quota window. Counted over versions rather
     * than files: rewriting an old file would otherwise never reach the quota, its
     * {@code files.created_at} being long past.
     */
    @Query("""
            SELECT COALESCE(SUM(v.sizeBytes), 0) FROM StoredFileVersion v, StoredFile f
            WHERE f.id = v.fileId AND f.userId = :userId AND v.createdAt >= :since
            """)
    long sumBytesSince(@Param("userId") UUID userId, @Param("since") LocalDateTime since);
}
