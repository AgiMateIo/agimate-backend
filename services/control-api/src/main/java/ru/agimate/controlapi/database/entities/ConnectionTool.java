package ru.agimate.controlapi.database.entities;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;
import ru.agimate.common.persistence.BaseEntity;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Тул динамического экземпляра коннектора (MCP-сервер, device-app). Обобщает прежний кэш
 * {@code mcp_tool}: набор открывается в рантайме ({@code tools/list} / device link) и кэшируется
 * здесь, чтобы воркер и UI политик видели список без обращения к источнику на горячем пути.
 *
 * <p>Схемы — сырым JSON-текстом для фиделити произвольной JSON Schema. Бизнес-ключ
 * {@code (connection_id, name)} — partial unique {@code WHERE deleted_at IS NULL}.
 */
@Entity
@Table(name = "connection_tools")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConnectionTool extends BaseEntity {

    @Id
    @Generated
    @ColumnDefault("uuidv7()")
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "connection_id", nullable = false)
    private UUID connectionId;

    @Column(name = "name", nullable = false, columnDefinition = "TEXT")
    private String name;

    @Column(name = "title", columnDefinition = "TEXT")
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /** Сырая JSON Schema входа как пришла с источника; {@code null} — пустая/отсутствует. */
    @Column(name = "input_schema", columnDefinition = "TEXT")
    private String inputSchema;

    /** Сырая JSON Schema выхода; {@code null}, если источник её не отдал. */
    @Column(name = "output_schema", columnDefinition = "TEXT")
    private String outputSchema;

    /** JSON хинтов {@code {readOnlyHint,destructiveHint,idempotentHint,openWorldHint}}; {@code null} — дефолты. */
    @Column(name = "annotations", columnDefinition = "TEXT")
    private String annotations;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;
}
