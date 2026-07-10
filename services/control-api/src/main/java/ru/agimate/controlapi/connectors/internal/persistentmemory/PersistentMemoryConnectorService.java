package ru.agimate.controlapi.connectors.internal.persistentmemory;

import org.springframework.stereotype.Component;
import ru.agimate.controlapi.connectors.core.BaseConnectorHandler;
import ru.agimate.controlapi.connectors.core.ConnectorEnv;
import ru.agimate.controlapi.connectors.core.InternalConnectorHandler;
import ru.agimate.controlapi.connectors.core.PromptBlockProvider;
import ru.agimate.controlapi.connectors.core.TriggerProvider;
import ru.agimate.controlapi.connectors.core.dto.PromptBlock;
import ru.agimate.controlapi.connectors.core.dto.TriggerSpec;
import ru.agimate.controlapi.database.entities.PersistentMemoryCold;
import ru.agimate.controlapi.database.entities.PersistentMemoryHot;
import ru.agimate.controlapi.database.enums.ExecutionLocus;
import ru.agimate.controlapi.database.enums.IdentityScope;
import ru.agimate.controlapi.database.enums.ToolBinding;
import ru.agimate.controlapi.database.enums.TransportDirection;
import ru.agimate.controlapi.database.model.ConnectorCapabilities;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

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
 *
 * <p>{@link PromptBlockProvider}: cold-память — SYSTEM-блок {@code memory} (attr {@code version} для CAS
 * в {@code update_memory}), hot-заметки — USER-блок {@code memory_notes}.
 */
@Component
public class PersistentMemoryConnectorService extends BaseConnectorHandler
        implements InternalConnectorHandler, TriggerProvider, PromptBlockProvider {

    public static final String CONNECTOR_CODE = "persist-memory";

    public static final String MEMORY_BLOCK = "memory";
    public static final String NOTES_BLOCK = "memory_notes";

    private final PersistentMemoryService memoryService;

    public PersistentMemoryConnectorService(PersistentMemoryToolService toolService,
                                            PersistentMemoryService memoryService) {
        super(toolService);
        this.memoryService = memoryService;
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

    @Override
    public List<PromptBlock> promptBlocks(ConnectorEnv env) {
        UUID scopeId = parseConnectionId(env)
                .flatMap(memoryService::scopeIdForConnection)
                .orElse(null);
        if (scopeId == null) {
            return List.of();
        }
        List<PromptBlock> blocks = new ArrayList<>(2);
        PersistentMemoryCold cold = memoryService.getCold(scopeId).orElse(null);
        if (cold != null && !cold.getContent().isBlank()) {
            blocks.add(PromptBlock.system(MEMORY_BLOCK, cold.getContent().strip(),
                    Map.of("version", String.valueOf(cold.getVersion()))));
        }
        String notes = renderNotes(memoryService.getNotes(scopeId));
        if (notes != null) {
            blocks.add(PromptBlock.user(NOTES_BLOCK, notes));
        }
        return blocks;
    }

    private static Optional<UUID> parseConnectionId(ConnectorEnv env) {
        try {
            return Optional.of(UUID.fromString(env.connectionId()));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static String renderNotes(List<PersistentMemoryHot> notes) {
        List<String> lines = notes.stream()
                .map(n -> n.getContent().strip())
                .filter(content -> !content.isEmpty())
                .map(content -> "- " + content)
                .toList();
        return lines.isEmpty() ? null : String.join("\n", lines);
    }
}
