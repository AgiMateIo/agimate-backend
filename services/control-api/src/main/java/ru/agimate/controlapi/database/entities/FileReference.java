package ru.agimate.controlapi.database.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.generator.EventType;
import org.hibernate.annotations.Generated;
import ru.agimate.common.persistence.BaseEntity;
import ru.agimate.controlapi.database.enums.FileReferenceKind;

import java.util.UUID;

/**
 * Where a file showed up (docs/connectors/files.md): the conversation it was attached to and the
 * agent that saw it. Provenance and navigation — never an access check, which stays user-wide on
 * {@code files.user_id}.
 *
 * <p>Rows are written by the funnels a file passes through, never by the upload: putting a file into
 * the layer is not yet using it.
 *
 * <p>The unique key {@code uq_file_references_file_session_kind} is
 * {@code (file_id, session_id, kind) NULLS NOT DISTINCT} and is declared in the migration alone —
 * neither {@code @Table(uniqueConstraints)} nor Liquibase's {@code addUniqueConstraint} can express
 * the NULL treatment, and a duplicate here would describe a constraint the database does not have.
 */
@Entity
@Table(name = "file_references")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileReference extends BaseEntity {

    @Id
    @Generated(event = EventType.INSERT)
    @ColumnDefault("uuidv7()")
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "file_id", nullable = false, updatable = false)
    private UUID fileId;

    /** The conversation; {@code null} for a file produced outside a channel flow. */
    @Column(name = "session_id", updatable = false)
    private UUID sessionId;

    /** {@code null} where the producer had no agent — a declarative job, a webhook. */
    @Column(name = "agent_id", updatable = false)
    private UUID agentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, columnDefinition = "TEXT")
    private FileReferenceKind kind;
}
