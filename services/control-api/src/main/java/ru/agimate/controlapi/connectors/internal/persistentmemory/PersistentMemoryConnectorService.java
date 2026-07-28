package ru.agimate.controlapi.connectors.internal.persistentmemory;

import org.springframework.stereotype.Component;
import ru.agimate.controlapi.connectors.core.BaseConnectorHandler;
import ru.agimate.controlapi.connectors.core.ConnectorEnv;
import ru.agimate.controlapi.connectors.core.InternalConnectorHandler;
import ru.agimate.controlapi.connectors.core.PromptBlockProvider;
import ru.agimate.controlapi.connectors.core.TriggerProvider;
import ru.agimate.controlapi.connectors.core.dto.ContextDirectives;
import ru.agimate.controlapi.connectors.core.dto.PromptBlock;
import ru.agimate.controlapi.connectors.core.dto.TriggerSpec;
import ru.agimate.controlapi.database.entities.PersistentMemoryCold;
import ru.agimate.controlapi.database.entities.PersistentMemoryHot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Facade of the persistent memory connector: hot/cold memory per scope. The tools (get/update/note)
 * and the hidden daily and hourly jobs live in {@link PersistentMemoryToolService}.
 *
 * <p>Memory is personal: the space belongs to an agent, and its content is keyed by {@code agentId}
 * (resolved from {@code ConnectorEnv} at call time). The connection is a mode row, one per user; the
 * declarative {@code @Job}s (daily/consolidation) are registered on it and walk the spaces of every
 * bound agent (a {@code ConnectorCreatedEvent} when the row is materialised).
 *
 * <p>Triggers are addressed to the bound agents (audience): {@code notes-by-session} — collect a
 * session's notes, {@code consolidate} — fold the accumulated notes into cold.
 *
 * <p>{@link PromptBlockProvider}: cold memory is the SYSTEM block {@code memory} (with the attr
 * {@code version} for the CAS in {@code update_memory}), and hot notes are the USER block
 * {@code memory_notes}.
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

    @Override
    public String connectorDescription() {
        return "Долговременная память агента: заметки по ходу диалога и сжатая выжимка из них, "
                + "которая переживает сессии.";
    }

    /**
     * Minimal context for the memory jobs: the material is already in {@code data}
     * (messages/notes) — no history is needed; of the tools, memory alone suffices
     * ({@code ownConnectionTools}, skill tools off). The bodies of the matching skills stay (the route
     * base) — the memory skill is itself the processing instruction.
     */
    private static final ContextDirectives MEMORY_TASK_CONTEXT = ContextDirectives.builder()
            .skillTools(false)
            .ownConnectionTools(true)
            .historyLimit(0)
            .build();

    @Override
    public Map<String, TriggerSpec> getTriggers() {
        return Map.of(
                PersistentMemoryToolService.NOTES_TRIGGER, new TriggerSpec(
                        "Build memory notes from the messages of a session active in the last 24h",
                        List.of("sessionId", "messages"), MEMORY_TASK_CONTEXT),
                PersistentMemoryToolService.CONSOLIDATE_TRIGGER, new TriggerSpec(
                        "Consolidate accumulated hot notes into cold memory",
                        List.of("consolidationId", "notes"), MEMORY_TASK_CONTEXT));
    }

    @Override
    public List<PromptBlock> promptBlocks(ConnectorEnv env) {
        // Memory is personal: the space is the calling agent.
        UUID scopeId = env.agentId();
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

    private static String renderNotes(List<PersistentMemoryHot> notes) {
        List<String> lines = notes.stream()
                .map(n -> n.getContent().strip())
                .filter(content -> !content.isEmpty())
                .map(content -> "- " + content)
                .toList();
        return lines.isEmpty() ? null : String.join("\n", lines);
    }
}
