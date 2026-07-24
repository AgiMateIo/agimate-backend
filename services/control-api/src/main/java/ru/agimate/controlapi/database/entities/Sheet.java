package ru.agimate.controlapi.database.entities;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import ru.agimate.common.persistence.BaseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Таблица агента в sheets-коннекторе: объявленная схема колонок + строки ({@link SheetRow}).
 *
 * <p>Схема объявлена намеренно — в отличие от свободной сетки ячеек Excel. Тип колонки известен,
 * поэтому каст в SQL-агрегации безопасен, а имя колонки, пришедшее аргументом от LLM, проверяется
 * по whitelist схемы (см. {@code SheetQueryBuilder}).
 *
 * <p>Владение — AGENT scope: {@code scope_id} = agentId (как у persistent memory). {@code user_id} —
 * сквозная граница доступа.
 */
@Entity
@Table(name = "sheets", uniqueConstraints =
        @UniqueConstraint(name = "uq_sheets_scope_name", columnNames = {"scope_id", "name"}))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Sheet extends BaseEntity {

    @Id
    @Generated
    @ColumnDefault("uuidv7()")
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /** Носитель листа: agentId (AGENT scope). */
    @Column(name = "scope_id", nullable = false)
    private UUID scopeId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** Машинный код листа (slug), уникален в паре со {@code scopeId}. */
    @Column(name = "name", nullable = false, columnDefinition = "TEXT")
    private String name;

    @Column(name = "title", nullable = false, columnDefinition = "TEXT")
    private String title;

    /** Схема: {@code [{name,title,type,unit}]}, type — number|text|date|bool. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "columns", nullable = false, columnDefinition = "JSONB")
    private List<Map<String, Object>> columns;
}
