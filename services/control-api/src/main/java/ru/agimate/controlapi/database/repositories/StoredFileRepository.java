package ru.agimate.controlapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.agimate.controlapi.database.entities.StoredFile;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface StoredFileRepository extends JpaRepository<StoredFile, UUID> {

    /** Sum of bytes uploaded by a user since {@code since} (the daily quota window). */
    @Query("select coalesce(sum(f.sizeBytes), 0) from StoredFile f where f.userId = :userId and f.createdAt >= :since")
    long sumBytesSince(@Param("userId") UUID userId, @Param("since") LocalDateTime since);

    /**
     * A batch to delete, under a lock: expired READY plus abandoned UPLOADING (older than an hour).
     * {@code FOR UPDATE SKIP LOCKED} keeps several cleanup instances from fighting over the same
     * rows; call it inside a transaction only.
     */
    @Query(value = """
            SELECT * FROM files
            WHERE (status = 'READY' AND expires_at < now())
               OR (status = 'UPLOADING' AND created_at < now() - interval '1 hour')
            ORDER BY expires_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<StoredFile> claimPurgeBatch(@Param("limit") int limit);
}
