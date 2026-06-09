package ru.agimate.controlapi.connectors.internal.time;

import org.springframework.stereotype.Component;
import ru.agimate.controlapi.connectors.core.BaseConnectorHandler;
import ru.agimate.controlapi.connectors.core.InternalConnectorHandler;

/**
 * Фасад time-коннектора: текущие дата и время. Тулы живут в {@link TimeToolService},
 * фоновых тасок и триггеров нет.
 */
@Component
public class TimeConnectorService extends BaseConnectorHandler implements InternalConnectorHandler {

    public static final String CONNECTOR_CODE = "time";

    public TimeConnectorService(TimeToolService toolService) {
        super(toolService);
    }

    @Override
    public String connectorCode() {
        return CONNECTOR_CODE;
    }

    @Override
    public String connectorName() {
        return "Time";
    }
}
