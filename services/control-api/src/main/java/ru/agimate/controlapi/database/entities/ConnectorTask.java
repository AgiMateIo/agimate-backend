package ru.agimate.controlapi.database.entities;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import ru.agimate.common.persistence.BaseEntity;
import ru.agimate.controlapi.database.enums.ConnectorTaskScopeKind;
import ru.agimate.controlapi.database.enums.ConnectorTaskType;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Registry фоновых задач коннекторов — источник истины для {@code ConnectorTaskScheduler}.
 * Каждая строка описывает один экземпляр задачи, который должен крутиться, пока {@code enabled=true}.
 *
 * <p>Сам исполняемый код в БД не хранится — он живёт в handler'е и резолвится при старте по паре
 * {@code (connectorCode, taskCode)}. В {@link #config} лежат только параметры расписания.
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

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_kind", nullable = false, columnDefinition = "TEXT")
    private ConnectorTaskScopeKind scopeKind;

    /** UUID интеграции / пользователя; {@code null} для {@link ConnectorTaskScopeKind#GLOBAL}. */
    @Column(name = "scope_id")
    private UUID scopeId;

    /** Бизнес‑идентификатор аккаунта в коннекторе (имя бота, имя/id доски и т.п.). */
    @Column(name = "identity", columnDefinition = "TEXT")
    private String identity;

    @Column(name = "task_code", nullable = false, columnDefinition = "TEXT")
    private String taskCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "task_type", nullable = false, columnDefinition = "TEXT")
    private ConnectorTaskType taskType;

    /**
     * Параметры расписания/поведения: {@code interval}, {@code initialDelay} для PERIODIC;
     * {@code cron}, {@code zone} для CRON; {@code backoff} для LONG_RUNNING.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config", nullable = false, columnDefinition = "JSONB")
    private Map<String, Object> config;

    @Column(name = "enabled", nullable = false)
    @Builder.Default
    private Boolean enabled = true;

    /** Когда задача была в последний раз запущена шедулером (информационно). */
    @Column(name = "last_started_at")
    private LocalDateTime lastStartedAt;

    /** Последнее наблюдённое сообщение об ошибке (информационно, для диагностики). */
    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;
}
