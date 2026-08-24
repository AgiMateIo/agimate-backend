package ru.agimate.controlapi.database.repositories;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.agimate.controlapi.database.entities.StoredFile;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface StoredFileRepository extends JpaRepository<StoredFile, UUID> {

    /**
     * A page of the files their owner may see: the same three filters as
     * {@code FileStorageService.findReadable} (own + READY + not expired), so a listing never offers a
     * file that would refuse to open. Every filter is {@code null}-able, and {@code null} means «no
     * filter».
     *
     * <p>The context filters go through {@code EXISTS} rather than a join: a file that showed up in
     * one conversation twice must appear in the page once. They stay cheap because the driving
     * predicate is {@code user_id} — {@code idx_files_user_id_created_at} narrows the scan to one
     * owner, and the subqueries only filter what is left.
     *
     * @param agentId   the agent the file is related to — it produced the file ({@code files.agent_id})
     *                  or saw it ({@code file_references}). Deliberately not «produced by» alone: a
     *                  listing filtered by an agent is expected to hold what the user sent it, and
     *                  the producer stays visible in the row itself
     * @param sessionId the conversation the file showed up in
     * @param name      a case-insensitive substring of the name
     * @param now       the moment TTL is judged against — a parameter rather than
     *                  {@code CURRENT_TIMESTAMP} so the comparison stays on the entity's own type
     */
    @Query("""
            SELECT f FROM StoredFile f
            WHERE f.userId = :userId
            AND f.status = ru.agimate.controlapi.database.enums.FileStatus.READY
            AND f.expiresAt > :now
            AND (:agentId IS NULL OR f.agentId = :agentId
                 OR EXISTS (SELECT 1 FROM FileReference r
                            WHERE r.fileId = f.id AND r.agentId = :agentId))
            AND (:sessionId IS NULL
                 OR EXISTS (SELECT 1 FROM FileReference s
                            WHERE s.fileId = f.id AND s.sessionId = :sessionId))
            AND (:name IS NULL OR LOWER(f.name) LIKE LOWER(CONCAT('%', CAST(:name AS string), '%')))
            ORDER BY f.createdAt DESC
            """)
    Page<StoredFile> findVisible(@Param("userId") UUID userId, @Param("agentId") UUID agentId,
                                 @Param("sessionId") UUID sessionId, @Param("name") String name,
                                 @Param("now") LocalDateTime now, Pageable pageable);

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
