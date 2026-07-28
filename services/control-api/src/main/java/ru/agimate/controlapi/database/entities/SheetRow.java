package ru.agimate.controlapi.database.entities;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import ru.agimate.common.persistence.BaseEntity;

import java.util.Map;
import java.util.UUID;

/**
 * A row of a {@link Sheet}: cell values for the declared columns.
 *
 * <p>Row order is not stored — unlike in Excel it carries no meaning: sorting is decided by the query
 * ({@code query(sortBy)}). The database column is named {@code data} rather than {@code values}
 * because VALUES is a PostgreSQL reserved word; in the tools' JSON output the key stays {@code values}.
 */
@Entity
@Table(name = "sheet_rows")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SheetRow extends BaseEntity {

    @Id
    @Generated
    @ColumnDefault("uuidv7()")
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "sheet_id", nullable = false)
    private UUID sheetId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** Cell values {@code {column: value}}; a missing key means an empty cell. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "data", nullable = false, columnDefinition = "JSONB")
    private Map<String, Object> values;
}
