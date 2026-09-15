package ru.agimate.controlapi.database.repositories;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
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

    /**
     * Claims the next write attempt of a file: the number comes back only while the file is still at
     * {@code expectedVersion} and nobody else is writing it — or the one writing has been silent since
     * {@code staleBefore}, and is presumed dead. {@code null} — refused, and so is a file that expired
     * or was deleted since the caller read it.
     *
     * <p>The number is never handed out twice, so the blob key derived from it belongs to this attempt
     * alone: two writers can never upload under one key, which is the whole point of claiming before
     * uploading rather than checking at commit. {@code updated_at} doubles as the claim time.
     *
     * <p>Native for {@code RETURNING}; annotated because a declared query method gets no transaction
     * of its own, and the caller holds none across the upload that follows.
     */
    @Transactional
    @Query(value = """
            UPDATE files
            SET claimed_version = claimed_version + 1, updated_at = :now
            WHERE id = :id
              AND status = 'READY'
              AND expires_at > :now
              AND version = :expectedVersion
              AND (claimed_version = version OR updated_at < :staleBefore)
            RETURNING claimed_version
            """, nativeQuery = true)
    Integer claimVersion(@Param("id") UUID id, @Param("expectedVersion") int expectedVersion,
                         @Param("staleBefore") LocalDateTime staleBefore, @Param("now") LocalDateTime now);

    /**
     * Makes a claimed attempt the current version and journals it, in one statement. Zero — the claim
     * is no longer this attempt's: it was taken over as stale, and whatever it uploaded is garbage.
     * {@code expires_at} only moves forward: a file edited today must not expire the day after
     * because it was first written a week ago. It never moves a file back to life: one deleted
     * mid-upload (deletion is {@code expires_at = now}) refuses the commit.
     *
     * <p>The journal row is selected from {@code RETURNING} rather than from the parameters: a null
     * parameter in a select list has no type, and Postgres would read it as text.
     */
    @Transactional
    @Modifying
    @Query(value = """
            WITH moved AS (
                UPDATE files
                SET version = :version, mime = :mime, size = :size, sha256 = :sha256,
                    agent_id = :agentId, origin = :origin, name = COALESCE(CAST(:name AS text), name),
                    expires_at = GREATEST(expires_at, :expiresAt), updated_at = :now
                WHERE id = :id AND claimed_version = :version AND status = 'READY' AND expires_at > :now
                RETURNING id, version, mime, size, sha256, agent_id, origin, updated_at
            )
            INSERT INTO file_versions (file_id, version, mime, size, sha256, agent_id, origin, created_at, updated_at)
            SELECT id, version, mime, size, sha256, agent_id, origin, updated_at, updated_at FROM moved
            """, nativeQuery = true)
    int commitVersion(@Param("id") UUID id, @Param("version") int version, @Param("mime") String mime,
                      @Param("size") long size, @Param("sha256") String sha256,
                      @Param("agentId") UUID agentId, @Param("origin") String origin,
                      @Param("name") String name, @Param("expiresAt") LocalDateTime expiresAt,
                      @Param("now") LocalDateTime now);

    /**
     * A write that made no version — the contents were already there: the name still changes when
     * given (it belongs to the document), and the expiry moves forward as on any write.
     */
    @Transactional
    @Modifying
    @Query(value = """
            UPDATE files
            SET name = COALESCE(CAST(:name AS text), name),
                expires_at = GREATEST(expires_at, :expiresAt), updated_at = :now
            WHERE id = :id AND status = 'READY' AND expires_at > :now
            """, nativeQuery = true)
    int touch(@Param("id") UUID id, @Param("name") String name, @Param("expiresAt") LocalDateTime expiresAt,
              @Param("now") LocalDateTime now);

    /**
     * Deletion ahead of TTL: the row only expires, and the sweep takes the blobs. A targeted update
     * rather than saving the entity — a full-row write would put back the version columns it read,
     * over a write that committed in between.
     */
    @Transactional
    @Modifying
    @Query("UPDATE StoredFile f SET f.expiresAt = :now, f.updatedAt = :now WHERE f.id = :id")
    int expire(@Param("id") UUID id, @Param("now") LocalDateTime now);

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
