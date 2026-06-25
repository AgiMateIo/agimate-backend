package ru.agimate.controlapi.connectors.internal.persistentmemory;

import org.springframework.stereotype.Component;
import ru.agimate.controlapi.connectors.core.BaseConnectorHandler;
import ru.agimate.controlapi.connectors.core.InternalConnectorHandler;
import ru.agimate.controlapi.connectors.core.dto.TriggerSpec;

import java.util.List;
import java.util.Map;

/**
 * Фасад коннектора persistent memory: hot/cold память агента. Тулы (get/update/note) и скрытые
 * дневная/часовая задачи живут в {@link PersistentMemoryToolService}.
 *
 * <p>Per-agent: декларативные {@code @Job} (daily/consolidation) регистрируются на {@code identity =
 * agentId} по {@code ConnectorCreatedEvent}, который издаёт {@link MemoryEnablementListener} при
 * выдаче агенту ALLOW-политики на этот коннектор.
 *
 * <p>Триггеры адресуются обратно агенту (audience): {@code notes-by-session} — собрать заметки по
 * сессии, {@code consolidate} — свернуть накопленные заметки в cold.
 */
@Component
public class PersistentMemoryConnectorService extends BaseConnectorHandler implements InternalConnectorHandler {

    public static final String CONNECTOR_CODE = "persist-memory";

    public PersistentMemoryConnectorService(PersistentMemoryToolService toolService) {
        super(toolService);
    }

    @Override
    public String connectorCode() {
        return CONNECTOR_CODE;
    }

    @Override
    public String connectorName() {
        return "Persistent Memory";
    }

    @Override
    public Map<String, TriggerSpec> getTriggers() {
        return Map.of(
                PersistentMemoryToolService.NOTES_TRIGGER, new TriggerSpec(
                        "Build memory notes from the messages of a session active in the last 24h",
                        List.of("sessionId", "messages")),
                PersistentMemoryToolService.CONSOLIDATE_TRIGGER, new TriggerSpec(
                        "Consolidate accumulated hot notes into cold memory",
                        List.of("consolidationId", "notes")));
    }
}
