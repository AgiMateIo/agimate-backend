package ru.agimate.controlapi.connectors.internal.board;

import org.springframework.stereotype.Component;
import ru.agimate.controlapi.connectors.core.BaseConnectorHandler;
import ru.agimate.controlapi.connectors.core.InternalConnectorHandler;
import ru.agimate.controlapi.service.board.BoardService;

/**
 * Фасад board-коннектора: тулы живут в {@link BoardToolService}, фоновых тасок и триггеров
 * на уровне SPI нет (board-триггеры публикует core-{@link BoardService} напрямую).
 *
 * <p><b>Владелец данных — команда вызывающего агента</b>: доска резолвится
 * {@code env.agentId → agenticTeam → board}, отдельного референта в connection нет
 * (см. чек-лист осей в docs/connectors/architecture.md).
 */
@Component
public class BoardConnectorService extends BaseConnectorHandler implements InternalConnectorHandler {

    public BoardConnectorService(BoardToolService toolService) {
        super(toolService);
    }

    @Override
    public String connectorCode() {
        return BoardService.CONNECTOR_CODE;
    }

    @Override
    public String connectorName() {
        return "Board";
    }

}
