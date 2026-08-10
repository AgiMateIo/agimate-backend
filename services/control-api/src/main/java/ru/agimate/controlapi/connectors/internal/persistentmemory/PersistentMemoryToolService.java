package ru.agimate.controlapi.connectors.internal.persistentmemory;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.agimate.controlapi.connectors.core.ConnectorEnv;
import ru.agimate.controlapi.connectors.core.ConnectorEnvHolder;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.connectors.core.annotation.Job;
import ru.agimate.controlapi.connectors.core.annotation.Tool;
import ru.agimate.controlapi.connectors.core.annotation.ToolAnnotations;
import ru.agimate.controlapi.connectors.core.annotation.ToolParam;
import ru.agimate.controlapi.database.entities.PersistentMemoryCold;
import ru.agimate.controlapi.database.entities.PersistentMemoryHot;
import ru.agimate.controlapi.database.enums.ChannelSessionMessageKind;
import ru.agimate.controlapi.database.enums.ConnectorJobType;
import ru.agimate.controlapi.database.repositories.ChannelSessionMessageRepository;
import ru.agimate.controlapi.service.trigger.Trigger;
import ru.agimate.controlapi.service.trigger.TriggerAudience;
import ru.agimate.controlapi.service.trigger.TriggerContext;
import ru.agimate.controlapi.service.trigger.TriggerRouterService;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tools and background jobs of persistent memory.
 *
 * <p>Tools (visible to the LLM): {@code get_memory}/{@code get_memory_notes} (reading cold/hot),
 * {@code save_memory_note} (appending a note to hot), {@code update_memory} (a CAS write of cold plus
 * atomic deletion of the consolidated batch's notes).
 *
 * <p>Hidden {@code @Job}s (per connection, {@code connection_id = connections.id}): {@code daily}
 * walks the agent's sessions of the past day and addresses a {@code notes_by_session} to it for each;
 * {@code consolidation} claims the accumulated notes single-flight once an hour and sends a
 * {@code consolidate}.
 */
@Component
@RequiredArgsConstructor
public class PersistentMemoryToolService {

    static final String DAILY_JOB = "daily";
    static final String CONSOLIDATION_JOB = "consolidation";
    static final String NOTES_TRIGGER = "notes_by_session";
    static final String CONSOLIDATE_TRIGGER = "consolidate";

    /** How long to wait before reclaiming an abandoned consolidation (the lease on claimed notes). */
    private static final long CONSOLIDATION_LEASE_SECONDS = 1_800;
    /** Window of the daily note collection. */
    private static final int NOTES_LOOKBACK_HOURS = 24;
    /** Firing the job is only a database read plus publishing triggers; the iteration is short. */
    private static final int JOB_TIMEOUT_SECONDS = 120;

    private final PersistentMemoryService memoryService;
    private final TriggerRouterService triggerRouterService;
    private final ChannelSessionMessageRepository messageRepository;

    // ===== Tools =====

    @Tool(name = "get_memory", description = "Get your consolidated (cold) memory with its version. "
            + "Pass the returned version to update_memory when you rewrite it.",
            annotations = @ToolAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> getMemory() {
        UUID scopeId = resolveScopeId(ConnectorEnvHolder.current());
        PersistentMemoryCold cold = memoryService.getCold(scopeId).orElse(null);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", cold == null ? "" : cold.getContent());
        result.put("version", cold == null ? 0 : cold.getVersion());
        return result;
    }

    @Tool(name = "get_memory_notes", description = "Get your pending (hot) memory notes — facts captured "
            + "but not yet consolidated into cold memory.",
            annotations = @ToolAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> getMemoryNotes() {
        UUID scopeId = resolveScopeId(ConnectorEnvHolder.current());
        List<Map<String, Object>> notes = memoryService.getNotes(scopeId).stream()
                .map(PersistentMemoryToolService::noteView)
                .toList();
        return Map.of("notes", notes);
    }

    @Tool(name = "save_memory_note", description = "Append a note to your hot memory (a fact worth "
            + "remembering). Notes are later consolidated into your cold memory.",
            annotations = @ToolAnnotations(openWorldHint = false))
    public Map<String, Object> saveMemoryNote(
            @ToolParam("The fact/note to remember") String text,
            @ToolParam(value = "Session this note came from (optional, for tracing)", required = false)
            String sessionId) {
        ConnectorEnv ctx = ConnectorEnvHolder.current();
        UUID scopeId = resolveScopeId(ctx);
        if (text == null || text.isBlank()) {
            throw new ConnectorException("text is required");
        }
        PersistentMemoryHot note = memoryService.addNote(scopeId, ctx.userId(), parseUuid(sessionId, "sessionId"), text);
        return Map.of("id", note.getId().toString());
    }

    @Tool(name = "update_memory", description = "Rewrite your consolidated (cold) memory. Pass the version "
            + "from get_memory (optimistic lock — on conflict re-read and retry). When consolidating, pass "
            + "consolidationId to atomically drop the notes you folded in.",
            annotations = @ToolAnnotations(idempotentHint = false, openWorldHint = false))
    public Map<String, Object> updateMemory(
            @ToolParam("The full new content of your cold memory") String text,
            @ToolParam(value = "Expected current version from get_memory (required once memory exists)",
                    required = false) Integer version,
            @ToolParam(value = "Consolidation id from the consolidate trigger; deletes its notes", required = false)
            String consolidationId) {
        ConnectorEnv ctx = ConnectorEnvHolder.current();
        UUID scopeId = resolveScopeId(ctx);
        if (text == null) {
            throw new ConnectorException("text is required");
        }
        memoryService.updateMemory(scopeId, ctx.userId(), text, version, parseUuid(consolidationId, "consolidationId"));
        return Map.of("ok", true);
    }

    // ===== Hidden background jobs (per connection, connectionId = connections.id) =====

    @Tool(name = DAILY_JOB, description = "Internal: emit per-session note requests for the last 24h")
    @Job(type = ConnectorJobType.CRON, cron = "0 0 3 * * *", timeoutSeconds = JOB_TIMEOUT_SECONDS)
    public void daily() {
        ConnectorEnv ctx = ConnectorEnvHolder.current();
        UUID connectionId = requireConnectionId(ctx);
        LocalDateTime since = LocalDateTime.now().minusHours(NOTES_LOOKBACK_HOURS);
        // Sessions are collected per bound agent; each session's notes are addressed to its agent and land in
        // that agent's personal space (save_memory_note resolves the scope from the env).
        for (UUID agentId : memoryService.boundAgents(connectionId)) {
            for (UUID sessionId : messageRepository.findSessionIdsByAgentSince(agentId, since)) {
                // Only the dialogue: PROGRESS is the channel's own markup (💭, «🔧 name»), useless for a
                // note and a template the model would learn to write calls out as text from.
                List<Map<String, Object>> messages = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId).stream()
                        .filter(m -> m.getKind() != ChannelSessionMessageKind.PROGRESS)
                        .map(m -> {
                            Map<String, Object> view = new LinkedHashMap<>();
                            view.put("kind", m.getKind().name());
                            view.put("text", m.getMessage());
                            return view;
                        })
                        .toList();
                if (messages.isEmpty()) {
                    continue;
                }
                routeToAgents(ctx, List.of(agentId), NOTES_TRIGGER,
                        Map.of("sessionId", sessionId.toString(), "messages", messages));
            }
        }
    }

    @Tool(name = CONSOLIDATION_JOB, description = "Internal: claim pending notes and request consolidation")
    @Job(type = ConnectorJobType.CRON, cron = "0 0 * * * *", timeoutSeconds = JOB_TIMEOUT_SECONDS)
    public void consolidation() {
        ConnectorEnv ctx = ConnectorEnvHolder.current();
        UUID connectionId = requireConnectionId(ctx);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime leaseThreshold = now.minusSeconds(CONSOLIDATION_LEASE_SECONDS);
        // The memory space is the agent; one job per connection row walks the spaces of every bound agent.
        // A space's consolidation is performed by its owner — the LLM summary is not duplicated. Single-flight
        // per space: we do not spawn a second consolidation while the previous one runs (cold uses a CAS); an
        // abandoned batch is reclaimed once its lease expires.
        for (UUID agentId : memoryService.boundAgents(connectionId)) {
            if (memoryService.hasInFlightConsolidation(agentId, leaseThreshold)) {
                continue;
            }
            UUID consolidationId = UUID.randomUUID();
            List<PersistentMemoryHot> claimed =
                    memoryService.claimNotesForConsolidation(agentId, consolidationId, now, leaseThreshold);
            if (claimed.isEmpty()) {
                continue;
            }
            List<Map<String, Object>> notes = claimed.stream()
                    .map(PersistentMemoryToolService::noteView)
                    .toList();
            routeToAgents(ctx, List.of(agentId), CONSOLIDATE_TRIGGER,
                    Map.of("consolidationId", consolidationId.toString(), "notes", notes));
        }
    }

    // ===== helpers =====

    /** Addresses a directed trigger to the bound agents (audience, with no channel — it is a background job). */
    private void routeToAgents(ConnectorEnv ctx, List<UUID> agentIds, String triggerName,
                               Map<String, Object> data) {
        if (agentIds.isEmpty()) {
            return;
        }
        Trigger trigger = Trigger.createDirected(
                PersistentMemoryConnectorService.CONNECTOR_CODE,
                ctx.connectionId(),
                triggerName,
                data,
                TriggerContext.audience(new TriggerAudience(null, agentIds)));
        triggerRouterService.routeTrigger(ctx.userId(), trigger);
    }

    /** The memory space is personal: the owner is the calling agent. */
    private static UUID resolveScopeId(ConnectorEnv ctx) {
        if (ctx.agentId() == null) {
            throw new ConnectorException("persist-memory tools require an agent context");
        }
        return ctx.agentId();
    }

    private static UUID requireConnectionId(ConnectorEnv ctx) {
        if (ctx.connectionId() == null) {
            throw new ConnectorException("persist-memory requires a connection connectionId");
        }
        try {
            return UUID.fromString(ctx.connectionId());
        } catch (IllegalArgumentException e) {
            throw new ConnectorException("Invalid connection connectionId: " + ctx.connectionId());
        }
    }

    private static Map<String, Object> noteView(PersistentMemoryHot note) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", note.getId().toString());
        view.put("content", note.getContent());
        view.put("sessionId", note.getSessionId() == null ? null : note.getSessionId().toString());
        return view;
    }

    private static UUID parseUuid(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new ConnectorException("Invalid " + field + ": " + value);
        }
    }
}
