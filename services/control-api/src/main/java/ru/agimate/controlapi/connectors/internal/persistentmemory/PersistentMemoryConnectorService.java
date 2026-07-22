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
 * Фасад коннектора persistent memory: hot/cold память на scope. Тулы (get/update/note) и скрытые
 * дневная/часовая задачи живут в {@link PersistentMemoryToolService}.
 *
 * <p>Память личная: пространство агента, контент ключуется {@code agentId} (резолв из
 * {@code ConnectorEnv} в момент вызова). Connection — строка-режим, одна на пользователя; на ней
 * зарегистрированы декларативные {@code @Job} (daily/consolidation), обходящие пространства всех
 * привязанных агентов ({@code ConnectorCreatedEvent} при материализации строки).
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

    /**
     * Минимальный контекст memory-тасок: материал уже в {@code data} (messages/notes) — история
     * не нужна; из тулов достаточно памяти ({@code ownConnectionTools}, скилл-тулы выключены).
     * Тела подошедших скиллов остаются (route-база) — memory-скилл и есть инструкция обработки.
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
        // Память личная: пространство = вызывающий агент.
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
