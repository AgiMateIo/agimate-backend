package ru.agimate.controlapi.connectors.internal.time;

import org.springframework.stereotype.Component;
import ru.agimate.controlapi.connectors.core.BaseConnectorHandler;
import ru.agimate.controlapi.connectors.core.InternalConnectorHandler;
import ru.agimate.controlapi.connectors.core.TriggerProvider;
import ru.agimate.controlapi.connectors.core.dto.ContextDirectives;
import ru.agimate.controlapi.connectors.core.dto.TriggerSpec;

import java.util.List;
import java.util.Map;

/**
 * Фасад time-коннектора: текущее время + планирование отложенных задач агента. Тулы и скрытая
 * таска-диспетчер живут в {@link TimeToolService}; единственный триггер — {@code due} (agent-facing {@code time.due})
 * (срок запланированной задачи), адресуемый агенту-инициатору.
 *
 * <p><b>Владелец данных — вызывающий агент</b>: задачи фильтруются/отменяются по {@code env.agentId},
 * строка задачи несёт снапшот инициатора (см. чек-лист осей в docs/connectors/architecture.md).
 */
@Component
public class TimeConnectorService extends BaseConnectorHandler
        implements InternalConnectorHandler, TriggerProvider {

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
    public String connectorDescription() {
        return "Текущее время и отложенные задачи: агент планирует действие на будущее "
                + "и сам возвращается к нему в срок.";
    }

    @Override
    public Map<String, TriggerSpec> getTriggers() {
        // PROMPT легитимен: data.prompt собирает наш fire() из строки job'а, авторство — сам агент.
        return Map.of(TimeToolService.DUE_TRIGGER, new TriggerSpec(
                "A scheduled task created via time.schedule is due", List.of("prompt"),
                ContextDirectives.builder()
                        .presentation(ContextDirectives.Presentation.PROMPT)
                        .promptParam("prompt")
                        .guidance("Ниже — текст отложенной задачи, которую ты сам ранее запланировал "
                                + "через time.schedule. Выполни её.")
                        .ownConnectionTools(true)
                        .build()));
    }
}
