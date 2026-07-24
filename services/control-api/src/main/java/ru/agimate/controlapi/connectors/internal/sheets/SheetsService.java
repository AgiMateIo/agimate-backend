package ru.agimate.controlapi.connectors.internal.sheets;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.connectors.internal.sheets.dto.SheetDtos.AddResult;
import ru.agimate.controlapi.connectors.internal.sheets.dto.SheetDtos.AggregateResult;
import ru.agimate.controlapi.connectors.internal.sheets.dto.SheetDtos.ColumnSpec;
import ru.agimate.controlapi.connectors.internal.sheets.dto.SheetDtos.Condition;
import ru.agimate.controlapi.connectors.internal.sheets.dto.SheetDtos.Metric;
import ru.agimate.controlapi.connectors.internal.sheets.dto.SheetDtos.OperationResult;
import ru.agimate.controlapi.connectors.internal.sheets.dto.SheetDtos.RowList;
import ru.agimate.controlapi.connectors.internal.sheets.dto.SheetDtos.RowView;
import ru.agimate.controlapi.connectors.internal.sheets.dto.SheetDtos.SheetBrief;
import ru.agimate.controlapi.connectors.internal.sheets.dto.SheetDtos.SheetDetail;
import ru.agimate.controlapi.database.entities.Sheet;
import ru.agimate.controlapi.database.entities.SheetRow;
import ru.agimate.controlapi.database.repositories.SheetRepository;
import ru.agimate.controlapi.database.repositories.SheetRowRepository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Доменная логика листов: схема, строки, выборка, агрегация. Внутри коннекторного слоя, поэтому
 * бросает только {@link ConnectorException} — его текст доходит до агента дословно, и все сообщения
 * об ошибках написаны так, чтобы агент починился сам (перечисляют существующие листы/колонки).
 *
 * <p>Владение — AGENT scope: все операции ключуются {@code scopeId} (= agentId вызывающего).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SheetsService {

    public static final int MAX_SHEETS = 32;
    public static final int MAX_ROWS_PER_CALL = 500;

    private final SheetRepository sheetRepository;
    private final SheetRowRepository sheetRowRepository;
    private final SheetQueryBuilder queryBuilder;

    // ===== схема =====

    public List<SheetBrief> listSheets(UUID scopeId) {
        List<Sheet> sheets = sheetRepository.findByScopeIdOrderByNameAsc(scopeId);
        if (sheets.isEmpty()) {
            return List.of();
        }
        Map<UUID, Long> counts = rowCounts(sheets);
        List<SheetBrief> briefs = new ArrayList<>(sheets.size());
        for (Sheet sheet : sheets) {
            briefs.add(new SheetBrief(sheet.getName(), sheet.getTitle(), SheetSchema.columns(sheet),
                    counts.getOrDefault(sheet.getId(), 0L)));
        }
        return briefs;
    }

    public Sheet requireSheet(UUID scopeId, String name) {
        if (name == null || name.isBlank()) {
            throw new ConnectorException("Parameter 'sheet' is required");
        }
        return sheetRepository.findByScopeIdAndName(scopeId, name.trim())
                .orElseThrow(() -> new ConnectorException("No sheet '" + name + "'. Existing sheets: "
                        + existingSheetNames(scopeId) + ". Create it with create_sheet"));
    }

    @Transactional
    public SheetDetail createSheet(UUID scopeId, UUID userId, String name, String title,
                                   List<ColumnSpec> columns) {
        String sheetName = name == null ? null : name.trim();
        SheetSchema.requireName(sheetName, "sheet name");
        if (columns == null || columns.isEmpty()) {
            throw new ConnectorException("At least one column is required");
        }
        if (columns.size() > SheetSchema.MAX_COLUMNS) {
            throw new ConnectorException("Too many columns: " + columns.size()
                    + ", max " + SheetSchema.MAX_COLUMNS);
        }
        if (sheetRepository.existsByScopeIdAndName(scopeId, sheetName)) {
            Sheet existing = requireSheet(scopeId, sheetName);
            throw new ConnectorException("Sheet '" + sheetName + "' already exists with columns: "
                    + SheetSchema.names(SheetSchema.columns(existing))
                    + ". Reuse it, extend it with add_columns, or pick another name");
        }
        if (sheetRepository.findByScopeIdOrderByNameAsc(scopeId).size() >= MAX_SHEETS) {
            throw new ConnectorException("Sheet limit reached (" + MAX_SHEETS
                    + "). Delete an unused sheet first");
        }

        List<ColumnSpec> normalized = normalizeColumns(columns, List.of());
        Sheet sheet = sheetRepository.save(Sheet.builder()
                .scopeId(scopeId)
                .userId(userId)
                .name(sheetName)
                .title(title == null || title.isBlank() ? sheetName : title.trim())
                .columns(SheetSchema.toStorage(normalized))
                .build());
        return new SheetDetail(sheet.getName(), sheet.getTitle(), normalized, 0L);
    }

    @Transactional
    public SheetDetail addColumns(UUID scopeId, String name, List<ColumnSpec> columns) {
        if (columns == null || columns.isEmpty()) {
            throw new ConnectorException("At least one column is required");
        }
        Sheet sheet = requireSheet(scopeId, name);
        List<ColumnSpec> existing = SheetSchema.columns(sheet);
        List<ColumnSpec> added = normalizeColumns(columns, existing);
        if (existing.size() + added.size() > SheetSchema.MAX_COLUMNS) {
            throw new ConnectorException("Too many columns: " + (existing.size() + added.size())
                    + ", max " + SheetSchema.MAX_COLUMNS);
        }
        List<ColumnSpec> merged = new ArrayList<>(existing);
        merged.addAll(added);
        sheet.setColumns(SheetSchema.toStorage(merged));
        // Существующие строки не трогаем: отсутствующий ключ в JSONB и есть пустая ячейка.
        return new SheetDetail(sheet.getName(), sheet.getTitle(), merged,
                sheetRowRepository.countBySheetId(sheet.getId()));
    }

    /**
     * Импорт: лист и строки одной транзакцией. Отдельно от {@link #addRows}, потому что там кап на
     * вызов агента (500), а импорт заливает готовый файл целиком.
     */
    @Transactional
    public SheetDetail importSheet(UUID scopeId, UUID userId, String name, String title,
                                   List<ColumnSpec> columns, List<Map<String, Object>> cells) {
        SheetDetail created = createSheet(scopeId, userId, name, title, columns);
        Sheet sheet = requireSheet(scopeId, created.name());
        List<SheetRow> rows = new ArrayList<>(cells.size());
        for (Map<String, Object> values : cells) {
            rows.add(SheetRow.builder().sheetId(sheet.getId()).userId(userId).values(values).build());
        }
        sheetRowRepository.saveAll(rows);
        return new SheetDetail(created.name(), created.title(), created.columns(), rows.size());
    }

    @Transactional
    public OperationResult deleteSheet(UUID scopeId, String name) {
        Sheet sheet = requireSheet(scopeId, name);
        long rows = sheetRowRepository.countBySheetId(sheet.getId());
        sheetRepository.delete(sheet); // строки уносит FK ON DELETE CASCADE
        return new OperationResult(true, "Deleted sheet '" + sheet.getName() + "' with " + rows + " row(s)");
    }

    // ===== строки =====

    @Transactional
    public AddResult addRows(UUID scopeId, UUID userId, String name, List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            throw new ConnectorException("At least one row is required");
        }
        if (rows.size() > MAX_ROWS_PER_CALL) {
            throw new ConnectorException("Too many rows in one call: " + rows.size()
                    + ", max " + MAX_ROWS_PER_CALL + ". Split into several add_rows calls");
        }
        Sheet sheet = requireSheet(scopeId, name);
        List<ColumnSpec> columns = SheetSchema.columns(sheet);

        List<SheetRow> entities = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            entities.add(SheetRow.builder()
                    .sheetId(sheet.getId())
                    .userId(userId)
                    .values(cells(columns, sheet.getName(), row, true))
                    .build());
        }
        List<SheetRow> saved = sheetRowRepository.saveAll(entities);
        return new AddResult(true, saved.size(), saved.stream().map(r -> r.getId().toString()).toList());
    }

    @Transactional
    public OperationResult updateRows(UUID scopeId, String name, List<String> ids,
                                      Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            throw new ConnectorException("Parameter 'values' is required — the cells to overwrite");
        }
        Sheet sheet = requireSheet(scopeId, name);
        List<ColumnSpec> columns = SheetSchema.columns(sheet);
        List<SheetRow> rows = sheetRowRepository.findBySheetIdAndIdIn(sheet.getId(), parseIds(ids));
        if (rows.isEmpty()) {
            throw new ConnectorException("No rows matched the given ids in sheet '" + sheet.getName()
                    + "'. Get real row ids from query first");
        }
        // Пустое значение = очистить ячейку, поэтому null-ы здесь значимы и сохраняются как удаление ключа.
        Map<String, Object> patch = cells(columns, sheet.getName(), values, false);
        for (SheetRow row : rows) {
            Map<String, Object> merged = new LinkedHashMap<>(row.getValues());
            patch.forEach((key, value) -> {
                if (value == null) {
                    merged.remove(key);
                } else {
                    merged.put(key, value);
                }
            });
            row.setValues(merged);
        }
        return new OperationResult(true, "Updated " + rows.size() + " row(s)");
    }

    @Transactional
    public OperationResult deleteRows(UUID scopeId, String name, List<String> ids) {
        Sheet sheet = requireSheet(scopeId, name);
        long deleted = sheetRowRepository.deleteBySheetIdAndIdIn(sheet.getId(), parseIds(ids));
        return new OperationResult(true, "Deleted " + deleted + " row(s)");
    }

    // ===== запросы =====

    public RowList query(UUID scopeId, String name, List<Condition> filter, String sortBy,
                         String sortDir, Integer limit) {
        Sheet sheet = requireSheet(scopeId, name);
        List<ColumnSpec> columns = SheetSchema.columns(sheet);
        SheetQueryBuilder.Selection selection =
                queryBuilder.select(sheet, columns, filter, sortBy, sortDir, limit);
        return new RowList(sheet.getName(), selection.rows().size(), selection.truncated(), selection.rows());
    }

    public AggregateResult aggregate(UUID scopeId, String name, String groupBy, String bucket,
                                     List<Metric> metrics, List<Condition> filter) {
        Sheet sheet = requireSheet(scopeId, name);
        List<ColumnSpec> columns = SheetSchema.columns(sheet);
        return new AggregateResult(sheet.getName(), groupBy,
                queryBuilder.aggregate(sheet, columns, groupBy, bucket, metrics, filter));
    }

    /** Строки для рендера/экспорта: тот же путь фильтрации, но без капа выдачи агенту. */
    public List<RowView> rowsFor(Sheet sheet, List<Condition> filter, String sortBy, String sortDir,
                                 int limit) {
        return queryBuilder.select(sheet, SheetSchema.columns(sheet), filter, sortBy, sortDir, limit).rows();
    }

    // ===== helpers =====

    /**
     * Значения ячеек по объявленной схеме. Неизвестная колонка — ошибка со списком существующих:
     * иначе агент тихо записал бы данные в никуда и решил, что всё сохранилось.
     */
    private static Map<String, Object> cells(List<ColumnSpec> columns, String sheetName,
                                             Map<String, Object> input, boolean dropEmpty) {
        Map<String, Object> cells = new LinkedHashMap<>();
        input.forEach((key, raw) -> {
            ColumnSpec column = SheetSchema.require(columns, key, sheetName);
            Object value = SheetSchema.coerceCell(column, raw);
            if (value != null || !dropEmpty) {
                cells.put(column.name(), value);
            }
        });
        return cells;
    }

    private static List<ColumnSpec> normalizeColumns(List<ColumnSpec> columns, List<ColumnSpec> existing) {
        List<ColumnSpec> normalized = new ArrayList<>(columns.size());
        List<String> seen = new ArrayList<>(existing.stream().map(ColumnSpec::name).toList());
        for (ColumnSpec column : columns) {
            ColumnSpec spec = SheetSchema.normalize(column);
            if (seen.contains(spec.name())) {
                throw new ConnectorException("Duplicate column: '" + spec.name() + "'");
            }
            seen.add(spec.name());
            normalized.add(spec);
        }
        return normalized;
    }

    private static List<UUID> parseIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new ConnectorException("Parameter 'ids' is required — row ids returned by query");
        }
        List<UUID> parsed = new ArrayList<>(ids.size());
        for (String id : ids) {
            try {
                parsed.add(UUID.fromString(id.trim()));
            } catch (IllegalArgumentException | NullPointerException e) {
                throw new ConnectorException("Invalid row id: '" + id + "'");
            }
        }
        return parsed;
    }

    private Map<UUID, Long> rowCounts(List<Sheet> sheets) {
        Map<UUID, Long> counts = new HashMap<>();
        for (Object[] row : sheetRowRepository.countRowsBySheetIds(sheets.stream().map(Sheet::getId).toList())) {
            counts.put((UUID) row[0], ((Number) row[1]).longValue());
        }
        return counts;
    }

    private String existingSheetNames(UUID scopeId) {
        List<Sheet> sheets = sheetRepository.findByScopeIdOrderByNameAsc(scopeId);
        return sheets.isEmpty() ? "(none yet)"
                : sheets.stream().map(Sheet::getName).reduce((a, b) -> a + ", " + b).orElse("");
    }
}
