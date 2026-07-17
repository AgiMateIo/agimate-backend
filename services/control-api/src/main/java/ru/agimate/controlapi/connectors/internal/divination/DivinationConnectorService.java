package ru.agimate.controlapi.connectors.internal.divination;

import org.springframework.stereotype.Component;
import ru.agimate.controlapi.connectors.core.BaseConnectorHandler;
import ru.agimate.controlapi.connectors.core.InternalConnectorHandler;

/**
 * Divination — внутренний коннектор детерминированной эзотерики: Матрица судьбы, нумерология
 * и Таро (колода из classpath-датасета). Тулы см. {@link DivinationToolService}.
 */
@Component
public class DivinationConnectorService extends BaseConnectorHandler implements InternalConnectorHandler {

    public static final String CONNECTOR_CODE = "divination";

    public DivinationConnectorService(DivinationToolService toolService) {
        super(toolService);
    }

    @Override
    public String connectorCode() {
        return CONNECTOR_CODE;
    }

    @Override
    public String connectorName() {
        return "Divination";
    }
}
