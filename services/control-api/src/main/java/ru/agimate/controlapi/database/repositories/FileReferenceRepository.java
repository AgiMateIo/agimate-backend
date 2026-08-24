package ru.agimate.controlapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.controlapi.database.entities.FileReference;

import java.util.UUID;

public interface FileReferenceRepository extends JpaRepository<FileReference, UUID> {

    /**
     * Records that a file showed up in a context, or leaves the existing row alone. An upsert rather
     * than find-then-save: the writers sit on retried paths (channel delivery, a replayed run), and
     * a read-then-write would race with itself under concurrent deliveries into one session.
     *
     * <p>{@code REQUIRES_NEW} because a reference is best-effort: a failed insert marks its
     * transaction rollback-only, and joining the caller's would turn a lost navigation row into a
     * lost delivery.
     *
     * @return 1 when a row was written, 0 when this context already knew the file
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Modifying
    @Query(value = """
            INSERT INTO file_references (file_id, session_id, agent_id, kind)
            VALUES (:fileId, :sessionId, :agentId, :kind)
            ON CONFLICT ON CONSTRAINT uq_file_references_file_session_kind DO NOTHING
            """, nativeQuery = true)
    int record(@Param("fileId") UUID fileId, @Param("sessionId") UUID sessionId,
               @Param("agentId") UUID agentId, @Param("kind") String kind);
}
