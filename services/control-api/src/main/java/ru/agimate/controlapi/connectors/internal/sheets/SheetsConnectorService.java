package ru.agimate.controlapi.connectors.internal.sheets;

import org.springframework.stereotype.Component;
import ru.agimate.controlapi.connectors.core.BaseConnectorHandler;
import ru.agimate.controlapi.connectors.core.ConnectorEnv;
import ru.agimate.controlapi.connectors.core.InternalConnectorHandler;
import ru.agimate.controlapi.connectors.core.PromptBlockProvider;
import ru.agimate.controlapi.connectors.core.dto.PromptBlock;
import ru.agimate.controlapi.connectors.internal.sheets.dto.SheetDtos.ColumnSpec;
import ru.agimate.controlapi.connectors.internal.sheets.dto.SheetDtos.SheetBrief;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Sheets — внутренний коннектор таблиц агента: объявленная схема колонок, фильтры и сводки по любой
 * колонке, графики и выгрузка в csv/xlsx. Тулы см. {@link SheetsToolService}.
 *
 * <p>Владение личное: пространство листов ключуется {@code agentId} (AGENT scope, резолв из
 * {@link ConnectorEnv} в момент вызова), сквозная граница доступа — {@code userId}.
 *
 * <p>{@link PromptBlockProvider}: SYSTEM-блок {@code sheets} со схемой листов. Он снимает главный
 * риск модели — не увидев своих таблиц, агент завёл бы дубль вместо записи в существующую. Блок
 * O(1) от объёма данных (строка на лист, не на запись) и дополнительно капнут по числу листов и
 * колонок: полный листинг живёт в туле {@code list_sheets}, а не в промпте.
 */
@Component
public class SheetsConnectorService extends BaseConnectorHandler implements InternalConnectorHandler,
        PromptBlockProvider {

    public static final String CONNECTOR_CODE = "sheets";
    public static final String SHEETS_BLOCK = "sheets";

    private static final int BLOCK_MAX_SHEETS = 8;
    private static final int BLOCK_MAX_COLUMNS = 12;

    private final SheetsService sheetsService;

    public SheetsConnectorService(SheetsToolService toolService, SheetsService sheetsService) {
        super(toolService);
        this.sheetsService = sheetsService;
    }

    @Override
    public String connectorCode() {
        return CONNECTOR_CODE;
    }

    @Override
    public String connectorName() {
        return "Sheets";
    }

    @Override
    public String connectorDescription() {
        return "Таблицы агента с объявленной схемой колонок: фильтры и сводки по любой колонке, "
                + "графики и выгрузка в CSV/XLSX.";
    }

    @Override
    public List<PromptBlock> promptBlocks(ConnectorEnv env) {
        UUID scopeId = env.agentId();
        if (scopeId == null) {
            return List.of();
        }
        List<SheetBrief> sheets = sheetsService.listSheets(scopeId);
        if (sheets.isEmpty()) {
            return List.of();
        }
        StringBuilder content = new StringBuilder();
        for (SheetBrief sheet : sheets.stream().limit(BLOCK_MAX_SHEETS).toList()) {
            content.append(render(sheet)).append('\n');
        }
        if (sheets.size() > BLOCK_MAX_SHEETS) {
            content.append("… and ").append(sheets.size() - BLOCK_MAX_SHEETS)
                    .append(" more — call list_sheets\n");
        }
        return List.of(PromptBlock.system(SHEETS_BLOCK, content.toString().strip(), Map.of()));
    }

    private static String render(SheetBrief sheet) {
        List<ColumnSpec> columns = sheet.columns();
        String rendered = columns.stream()
                .limit(BLOCK_MAX_COLUMNS)
                .map(SheetsConnectorService::render)
                .collect(Collectors.joining(", "));
        if (columns.size() > BLOCK_MAX_COLUMNS) {
            rendered += ", … (+" + (columns.size() - BLOCK_MAX_COLUMNS) + ")";
        }
        return "%s «%s» (%d rows): %s".formatted(sheet.name(), sheet.title(), sheet.rows(), rendered);
    }

    private static String render(ColumnSpec column) {
        return column.unit() == null || column.unit().isBlank()
                ? "%s:%s".formatted(column.name(), column.type())
                : "%s:%s %s".formatted(column.name(), column.type(), column.unit());
    }
}
