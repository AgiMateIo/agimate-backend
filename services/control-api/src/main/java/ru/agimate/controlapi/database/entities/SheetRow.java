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
 * Строка таблицы {@link Sheet}: значения ячеек по объявленным колонкам.
 *
 * <p>Порядок строк не хранится — в отличие от Excel он не значим: сортировку задаёт запрос
 * ({@code query(sortBy)}). Колонка БД называется {@code data}, а не {@code values}: VALUES —
 * зарезервированное слово PostgreSQL; в JSON-выдаче тулов ключ остаётся {@code values}.
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

    /** Значения ячеек {@code {колонка: значение}}; отсутствующий ключ — пустая ячейка. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "data", nullable = false, columnDefinition = "JSONB")
    private Map<String, Object> values;
}
