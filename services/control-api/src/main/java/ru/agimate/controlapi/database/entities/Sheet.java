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
 * An agent's table in the sheets connector: a declared column schema plus rows ({@link SheetRow}).
 *
 * <p>The schema is declared deliberately — unlike Excel's free grid of cells. The column's type is
 * known, so casting in SQL aggregation is safe, and a column name arriving as an argument from the
 * LLM is checked against the schema's whitelist (see {@code SheetQueryBuilder}).
 *
 * <p>Ownership is AGENT scope: {@code scope_id} = agentId (as with persistent memory).
 * {@code user_id} is the end-to-end access boundary.
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

    /** Owner of the sheet: agentId (AGENT scope). */
    @Column(name = "scope_id", nullable = false)
    private UUID scopeId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** Machine code of the sheet (slug), unique together with {@code scopeId}. */
    @Column(name = "name", nullable = false, columnDefinition = "TEXT")
    private String name;

    @Column(name = "title", nullable = false, columnDefinition = "TEXT")
    private String title;

    /** Schema: {@code [{name,title,type,unit}]}, where type is number|text|date|bool. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "columns", nullable = false, columnDefinition = "JSONB")
    private List<Map<String, Object>> columns;
}
