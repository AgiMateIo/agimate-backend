package ru.agimate.controlapi.database.entities;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import ru.agimate.common.persistence.BaseEntity;
import ru.agimate.controlapi.database.enums.ConnectorTaskStatus;
import ru.agimate.controlapi.database.enums.ConnectorTaskType;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Registry фоновых задач коннекторов — источник истины для pull‑based scheduler'а
 * {@code ConnectorTaskScheduler}.
 *
 * <p>Сам исполняемый код в БД не хранится: {@code task_name} диспатчится в {@code @Tool}-метод
 * tool-сервиса коннектора с аргументами {@link #taskArgs}. В {@link #taskConfig} лежат только
 * параметры расписания ({@code intervalSeconds}, {@code cron}, {@code zone}).
 *
 * <p>Уникальность бизнес-ключа обеспечивается partial unique index'ами в БД (см. миграцию
 * {@code 2026/06/08-02-connector-tasks.xml}): {@code (connector_code, task_name) WHERE identity
 * IS NULL} и {@code (connector_code, identity, task_name) WHERE identity IS NOT NULL} —
 * PostgreSQL не считает NULL = NULL, а {@code @UniqueConstraint} в JPA не умеет в partial.
 */
@Entity
@Table(name = "connector_tasks")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConnectorTask extends BaseEntity {

    @Id
    @Generated
    @ColumnDefault("uuidv7()")
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "connector_code", nullable = false, columnDefinition = "TEXT")
    private String connectorCode;

    /**
     * Идентификатор экземпляра коннектора: для integration — id из {@code integration_credentials}
     * строкой (как в {@code ToolUseLog}); {@code null} — глобальная задача internal-коннектора.
     */
    @Column(name = "identity", columnDefinition = "TEXT")
    private String identity;

    /** Имя задачи; диспатчится в {@code @Tool}-метод коннектора с этим именем. */
    @Column(name = "task_name", nullable = false, columnDefinition = "TEXT")
    private String taskName;

    @Enumerated(EnumType.STRING)
    @Column(name = "task_type", nullable = false, columnDefinition = "TEXT")
    private ConnectorTaskType taskType;

    /** Параметры расписания: {@code intervalSeconds} | {@code cron}, {@code zone}. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "task_config", nullable = false, columnDefinition = "JSONB")
    private Map<String, Object> taskConfig;

    /** Аргументы, передаваемые в метод при каждом запуске. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "task_args", nullable = false, columnDefinition = "JSONB")
    private Map<String, Object> taskArgs;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, columnDefinition = "TEXT")
    @Builder.Default
    private ConnectorTaskStatus status = ConnectorTaskStatus.PENDING;

    /** Когда поллер должен подхватить задачу в следующий раз; {@code null} для COMPLETED. */
    @Column(name = "next_run_at")
    private LocalDateTime nextRunAt;

    /** Лимит одной итерации в секундах: claim ставит {@code lease_until = now + timeout_seconds}. */
    @Column(name = "timeout_seconds", nullable = false)
    private Integer timeoutSeconds;

    /** До этого момента строка считается «занятой» текущей нодой; после — подхватывается заново. */
    @Column(name = "lease_until")
    private LocalDateTime leaseUntil;

    /** Когда в последний раз scheduler claim'нул строку (информационно). */
    @Column(name = "last_started_at")
    private LocalDateTime lastStartedAt;

    /** Последнее наблюдённое сообщение об ошибке (информационно). */
    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;
}
