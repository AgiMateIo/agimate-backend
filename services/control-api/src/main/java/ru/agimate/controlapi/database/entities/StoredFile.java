package ru.agimate.controlapi.database.entities;

import jakarta.persistence.*;
import lombok.*;
import ru.agimate.common.persistence.BaseEntity;
import ru.agimate.controlapi.database.enums.FileStatus;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Метаданные файла коннекторного слоя (docs/connectors/files.md). Байты живут в S3-совместимом
 * хранилище под ключом {@code user_id/agf_<id>}; строка — единственный источник владения (ABAC по
 * {@code user_id}) и lifecycle (TTL в {@code expires_at}).
 *
 * <p>{@code id} назначается приложением ({@code UUIDUtils.generateUUIDv8()}) — наружу уходит как
 * публичный идентификатор {@code agf_<uuid>} в результатах/параметрах тулов.
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

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, columnDefinition = "TEXT")
    private FileStatus status;

    @Column(name = "mime", nullable = false, columnDefinition = "TEXT")
    private String mime;

    @Column(name = "size", nullable = false)
    private Long sizeBytes;

    /** hex SHA-256 содержимого; заполняется по завершении загрузки (status=READY). */
    @Column(name = "sha256", columnDefinition = "TEXT")
    private String sha256;

    /** Провенанс: connector_code/tool/connection, породившие файл. */
    @Column(name = "origin", columnDefinition = "TEXT")
    private String origin;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;
}
