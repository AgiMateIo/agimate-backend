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
import ru.agimate.controlapi.database.enums.ConnectorJobType;
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
 * <p>A hidden {@code @Job} per connection ({@code connection_id = connections.id}):
 * {@code consolidation} claims the accumulated notes single-flight once a day and sends a
 * {@code consolidate} — daily by cadence, not by the clock, see the declaration. Notes come only from
 * the agent itself during a conversation; there is no second pass over the dialogues.
 */
@Component
@RequiredArgsConstructor
public class PersistentMemoryToolService {

    static final String CONSOLIDATION_JOB = "consolidation";
    static final String CONSOLIDATE_TRIGGER = "consolidate";

    /** How long to wait before reclaiming an abandoned consolidation (the lease on claimed notes). */
    private static final long CONSOLIDATION_LEASE_SECONDS = 1_800;
    /**
     * Cadence of the consolidation sweep. Once a day is enough because the pending notes already reach
     * the agent's context as their own block: folding them only keeps that block short, it is not what
     * makes a fact remembered.
     */
    private static final long CONSOLIDATION_INTERVAL_SECONDS = 86_400;
    /** Firing the job is only a database read plus publishing triggers; the iteration is short. */
    private static final int JOB_TIMEOUT_SECONDS = 120;

    private final PersistentMemoryService memoryService;
    private final TriggerRouterService triggerRouterService;

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
            annotations = @ToolAnnotations(destructiveHint = false, openWorldHint = false))
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
            annotations = @ToolAnnotations(destructiveHint = true, idempotentHint = false, openWorldHint = false))
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

    // PERIODIC rather than CRON on purpose. Nothing about consolidation is tied to the wall clock — only
    // the cadence matters — while a cron pins every row of every installation to the same second, so the
    // whole install woke up at :00 and spiked. A periodic row counts from its own completion, so the rows
    // stay as spread as they happen to start.
    @Tool(name = CONSOLIDATION_JOB, description = "Internal: claim pending notes and request consolidation")
    @Job(intervalSeconds = CONSOLIDATION_INTERVAL_SECONDS, timeoutSeconds = JOB_TIMEOUT_SECONDS)
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
