package ru.agimate.controlapi.database.entities;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;
import ru.agimate.common.persistence.BaseEntity;

import java.util.UUID;

/**
 * Кэш одного тула MCP-сервера. Тулы у MCP динамические и per-instance — открываются в рантайме
 * через {@code tools/list} и кэшируются здесь, чтобы воркер и UI политик видели список без
 * обращения к удалённому серверу на горячем пути. Синк — {@code McpToolDiscoveryListener}
 * (AFTER_COMMIT на create/modify интеграции) + ручной refresh.
 *
 * <p>Схемы хранятся сырым JSON-текстом как пришли с сервера — фиделити произвольной JSON Schema.
 * Бизнес-ключ {@code (integration_credentials_id, name)} — UNIQUE-констрейнт в БД (см. миграцию
 * {@code 2026/06/24-01-mcp-tools.xml}); дублируем его в {@code @Table(uniqueConstraints=...)}.
 */
@Entity
@Table(name = "mcp_tool", uniqueConstraints = @UniqueConstraint(
        name = "uq_mcp_tool_identity_name",
        columnNames = {"integration_credentials_id", "name"}))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpTool extends BaseEntity {

    @Id
    @Generated
    @ColumnDefault("uuidv7()")
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /** identity экземпляра коннектора — {@code integration_credentials.id}. */
    @Column(name = "integration_credentials_id", nullable = false)
    private UUID integrationCredentialsId;

    @Column(name = "name", nullable = false, columnDefinition = "TEXT")
    private String name;

    @Column(name = "title", columnDefinition = "TEXT")
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /** Сырая JSON Schema входа как пришла с MCP-сервера; {@code null} — пустая/отсутствует. */
    @Column(name = "input_schema", columnDefinition = "TEXT")
    private String inputSchema;

    /** Сырая JSON Schema выхода; {@code null}, если сервер её не отдал. */
    @Column(name = "output_schema", columnDefinition = "TEXT")
    private String outputSchema;

    /** JSON хинтов {@code {readOnlyHint,destructiveHint,idempotentHint,openWorldHint}}; {@code null} — дефолты. */
    @Column(name = "annotations", columnDefinition = "TEXT")
    private String annotations;
}
