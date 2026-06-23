package ru.agimate.controlapi.database.entities;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import ru.agimate.common.persistence.BaseEntity;
import ru.agimate.controlapi.database.enums.ConnectorJobKind;
import ru.agimate.controlapi.database.enums.ConnectorJobStatus;
import ru.agimate.controlapi.database.enums.ConnectorJobType;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Registry фоновых задач коннекторов — источник истины для pull‑based scheduler'а
 * {@code ConnectorJobScheduler}.
 *
 * <p>Сам исполняемый код в БД не хранится: {@code name} диспатчится в {@code @Tool}-метод
 * tool-сервиса коннектора с аргументами {@link #args}. В {@link #config} лежат только
 * параметры расписания ({@code intervalSeconds}, {@code cron}, {@code zone}).
 *
 * <p>Уникальность бизнес-ключа {@code (connector_code, identity, name)} действует только на
 * {@code kind = SYSTEM} (partial unique index, см. {@code 2026/06/11-01-connector-jobs-kind-paused.xml};
 * {@code @UniqueConstraint} в JPA не умеет в partial) — это инвариант reconcile-синка
 * ({@code findByBusinessKey} возвращает {@code Optional}). USER/AGENT-строки идентифицируются
 * собственным {@code id}, их на один {@code name} может быть много.
 */
@Entity
@Table(name = "connector_jobs")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConnectorJob extends BaseEntity {

    @Id
    @Generated
    @ColumnDefault("uuidv7()")
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "connector_code", nullable = false, columnDefinition = "TEXT")
    private String connectorCode;

    /**
     * Идентификатор экземпляра коннектора: для integration — id из {@code integration_credentials}
     * строкой (как в {@code ToolCallLog}); у динамических задач — identity tool-вызова инициатора
     * (восстанавливается в {@code ConnectorContext} на срабатывании); {@code null}, если экземпляр
     * не применим.
     */
    @Column(name = "identity", columnDefinition = "TEXT")
    private String identity;

    /** Владелец задачи: пользователь, создавший интеграцию, либо владелец агента-инициатора. */
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /**
     * Агент-инициатор для динамических задач (например, {@code time.schedule}): по нему list/cancel
     * и реконструкция {@link ru.agimate.controlapi.connectors.core.ConnectorContext} на срабатывании.
     * {@code null} у декларативных задач интеграции.
     */
    @Column(name = "agent_id")
    private UUID agentId;

    /**
     * Исходный канал агента-инициатора (снимок на момент планирования): куда динамическая таска
     * адресует ответ. Реконструируется в {@code ConnectorContext.channelId} на срабатывании.
     * {@code null}, если таска запланирована вне канального контекста.
     */
    @Column(name = "channel_id")
    private UUID channelId;

    /** Категория строки — см. {@link ConnectorJobKind}; определяет, действует ли бизнес-ключ. */
    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, columnDefinition = "TEXT")
    private ConnectorJobKind kind;

    /** Пауза пользователем: пока не {@code null}, scheduler строку не подхватывает. */
    @Column(name = "paused_at")
    private LocalDateTime pausedAt;

    /** Имя задачи; диспатчится в {@code @Tool}-метод коннектора с этим именем. */
    @Column(name = "name", nullable = false, columnDefinition = "TEXT")
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, columnDefinition = "TEXT")
    private ConnectorJobType type;

    /** Параметры расписания: {@code intervalSeconds} | {@code cron}, {@code zone}. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config", nullable = false, columnDefinition = "JSONB")
    private Map<String, Object> config;

    /** Аргументы, передаваемые в метод при каждом запуске. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "args", nullable = false, columnDefinition = "JSONB")
    private Map<String, Object> args;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, columnDefinition = "TEXT")
    @Builder.Default
    private ConnectorJobStatus status = ConnectorJobStatus.PENDING;

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
