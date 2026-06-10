package ru.agimate.controlapi.connectors.internal.time;

import org.springframework.stereotype.Component;
import ru.agimate.controlapi.connectors.core.BaseConnectorHandler;
import ru.agimate.controlapi.connectors.core.InternalConnectorHandler;
import ru.agimate.controlapi.connectors.core.dto.TriggerSpecification;

import java.util.List;
import java.util.Map;

/**
 * Фасад time-коннектора: текущее время + планирование отложенных задач агента. Тулы и скрытая
 * таска-диспетчер живут в {@link TimeToolService}; единственный триггер — {@code trigger.time.due}
 * (срок запланированной задачи), адресуемый агенту-инициатору.
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

    @Override
    public Map<String, TriggerSpecification> getTriggers() {
        return Map.of(TimeToolService.DUE_TRIGGER, new TriggerSpecification(
                "A scheduled task created via time.schedule is due", List.of("prompt")));
    }
}
