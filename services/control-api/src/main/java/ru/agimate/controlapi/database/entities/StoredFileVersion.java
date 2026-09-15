package ru.agimate.controlapi.database.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;
import ru.agimate.common.persistence.BaseEntity;

import java.util.UUID;

/**
 * One version of a {@link StoredFile}, the current one included (docs/decisions/files-and-pages.md).
 * The file row carries a copy of its current version, so this journal is read only where a version
 * is pinned — an attachment that must show what was sent — and by the daily quota.
 */
@Entity
@Table(name = "file_versions", uniqueConstraints = {
        @UniqueConstraint(name = "uq_file_versions_file_id_version", columnNames = {"file_id", "version"})
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StoredFileVersion extends BaseEntity {

    @Id
    @Generated(event = EventType.INSERT)
    @ColumnDefault("uuidv7()")
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "file_id", nullable = false, updatable = false)
    private UUID fileId;

    @Column(name = "version", nullable = false, updatable = false)
    private Integer version;

    @Column(name = "mime", nullable = false, updatable = false, columnDefinition = "TEXT")
    private String mime;

    @Column(name = "size", nullable = false, updatable = false)
    private Long sizeBytes;

    @Column(name = "sha256", updatable = false, columnDefinition = "TEXT")
    private String sha256;

    @Column(name = "agent_id", updatable = false)
    private UUID agentId;

    @Column(name = "origin", updatable = false, columnDefinition = "TEXT")
    private String origin;
}
