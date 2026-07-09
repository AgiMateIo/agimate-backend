package ru.agimate.controlapi.connectors.internal.persistentmemory;

import org.springframework.stereotype.Component;
import ru.agimate.controlapi.connectors.core.BaseConnectorHandler;
import ru.agimate.controlapi.connectors.core.InternalConnectorHandler;
import ru.agimate.controlapi.connectors.core.dto.TriggerSpec;
import ru.agimate.controlapi.database.enums.ExecutionLocus;
import ru.agimate.controlapi.database.enums.IdentityScope;
import ru.agimate.controlapi.database.enums.ToolBinding;
import ru.agimate.controlapi.database.enums.TransportDirection;
import ru.agimate.controlapi.database.model.ConnectorCapabilities;

import java.util.List;
import java.util.Map;

/**
 * Фасад коннектора persistent memory: hot/cold память на scope. Тулы (get/update/note) и скрытые
 * дневная/часовая задачи живут в {@link PersistentMemoryToolService}.
 *
 * <p>Память хранится по {@code connections.scope_id}: при {@code identity_scope = AGENT} — личная
 * (scope_id = agentId), при {@code TEAM} — общая для команды (scope_id = teamId). Привязка к агенту
 * ({@code agent_connections}) материализует экземпляр под выбранный scope и регистрирует
 * декларативные {@code @Job} (daily/consolidation) на {@code connectionId = connections.id}
 * ({@code ConnectorCreatedEvent} из {@code ConnectionBindingService}).
 *
 * <p>Триггеры адресуются привязанным агентам (audience): {@code notes-by-session} — собрать заметки по
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

    /** Память может быть личной (AGENT) или командной (TEAM) — выбирается при привязке. */
    @Override
    public ConnectorCapabilities capabilities() {
        return new ConnectorCapabilities(
                TransportDirection.OUTBOUND, ExecutionLocus.BACKEND, ToolBinding.STATIC,
                List.of(IdentityScope.AGENT, IdentityScope.TEAM), IdentityScope.AGENT);
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
