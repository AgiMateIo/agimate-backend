package ru.agimate.controlapi.database.entities;

import jakarta.persistence.*;
import lombok.*;
import ru.agimate.common.persistence.BaseEntity;
import ru.agimate.controlapi.database.enums.FileStatus;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Metadata of a file in the connector layer (docs/connectors/files.md). The bytes live in
 * S3-compatible storage under the key {@code user_id/agf_<id>}; the row is the single source of
 * ownership (ABAC by {@code user_id}) and of lifecycle (TTL in {@code expires_at}).
 *
 * <p>{@code id} is assigned by the application ({@code UUIDUtils.generateUUIDv8()}) — it goes out as
 * the public identifier {@code agf_<uuid>} in tool results and parameters.
 */
@Entity
@Table(name = "files")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StoredFile extends BaseEntity {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /**
     * The agent that produced the file — provenance, never an access check: files are shared across
     * the agents of one user by design (board comments, sheet cells). {@code null} where the producer
     * is unknown — ingest of an inbound message, an upload from a device or the webchat UI.
     */
    @Column(name = "agent_id", updatable = false)
    private UUID agentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, columnDefinition = "TEXT")
    private FileStatus status;

    @Column(name = "mime", nullable = false, columnDefinition = "TEXT")
    private String mime;

    @Column(name = "size", nullable = false)
    private Long sizeBytes;

    /** The file name as its producer knew it; {@code null} when there was none (see {@link ru.agimate.controlapi.storage.NewFile}). */
    @Column(name = "name", columnDefinition = "TEXT")
    private String name;

    /** hex SHA-256 of the contents; filled in once the upload completes (status=READY). */
    @Column(name = "sha256", columnDefinition = "TEXT")
    private String sha256;

    /** Provenance: the connector_code/tool/connection that produced the file. */
    @Column(name = "origin", columnDefinition = "TEXT")
    private String origin;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;
}
