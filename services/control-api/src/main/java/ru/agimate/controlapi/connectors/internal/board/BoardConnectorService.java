package ru.agimate.controlapi.connectors.internal.board;

import org.springframework.stereotype.Component;
import ru.agimate.controlapi.connectors.core.BaseConnectorHandler;
import ru.agimate.controlapi.connectors.core.InternalConnectorHandler;
import ru.agimate.controlapi.database.model.ConnectorTraits;
import ru.agimate.controlapi.database.enums.ExecutionLocus;
import ru.agimate.controlapi.database.enums.IdentityScope;
import ru.agimate.controlapi.database.enums.ToolBinding;
import ru.agimate.controlapi.database.enums.TransportDirection;
import ru.agimate.controlapi.service.board.BoardService;

import java.util.List;

/**
 * Фасад board-коннектора: тулы живут в {@link BoardToolService}, фоновых тасок и триггеров
 * на уровне SPI нет (board-триггеры публикует core-{@link BoardService} напрямую).
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

    /** Board шарится в рамках команды агента (scope_id = teamId). */
    @Override
    public ConnectorTraits traits() {
        return new ConnectorTraits(
                TransportDirection.OUTBOUND, ExecutionLocus.BACKEND, ToolBinding.STATIC,
                List.of(IdentityScope.TEAM), IdentityScope.TEAM);
    }
}
