package ru.agimate.controlapi.connectors.internal.platform;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.controlapi.abac.AccessEffect;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.connectors.core.annotation.Tool;
import ru.agimate.controlapi.connectors.core.annotation.ToolAnnotations;
import ru.agimate.controlapi.connectors.core.annotation.ToolParam;
import ru.agimate.controlapi.connectors.internal.platform.dto.PlatformObservabilityDtos.CancelRunResult;
import ru.agimate.controlapi.connectors.internal.platform.dto.PlatformObservabilityDtos.CancelSessionResult;
import ru.agimate.controlapi.connectors.internal.platform.dto.PlatformObservabilityDtos.RunBrief;
import ru.agimate.controlapi.connectors.internal.platform.dto.PlatformObservabilityDtos.RunList;
import ru.agimate.controlapi.connectors.internal.platform.dto.PlatformObservabilityDtos.RunPrompt;
import ru.agimate.controlapi.connectors.internal.platform.dto.PlatformObservabilityDtos.RunTurnItem;
import ru.agimate.controlapi.connectors.internal.platform.dto.PlatformObservabilityDtos.RunTurnList;
import ru.agimate.controlapi.connectors.internal.platform.dto.PlatformObservabilityDtos.SessionBrief;
import ru.agimate.controlapi.connectors.internal.platform.dto.PlatformObservabilityDtos.SessionList;
import ru.agimate.controlapi.connectors.internal.platform.dto.PlatformObservabilityDtos.ToolCallLogItem;
import ru.agimate.controlapi.connectors.internal.platform.dto.PlatformObservabilityDtos.ToolCallLogList;
import ru.agimate.controlapi.connectors.internal.platform.dto.PlatformObservabilityDtos.TriggerLogItem;
import ru.agimate.controlapi.connectors.internal.platform.dto.PlatformObservabilityDtos.TriggerLogList;
import ru.agimate.controlapi.connectors.internal.platform.dto.PlatformObservabilityDtos.WebhookDeliveryItem;
import ru.agimate.controlapi.connectors.internal.platform.dto.PlatformObservabilityDtos.WebhookDeliveryList;
import ru.agimate.controlapi.database.entities.AgentRun;
import ru.agimate.controlapi.database.entities.AgentRunTurn;
import ru.agimate.controlapi.database.entities.AgentSession;
import ru.agimate.controlapi.database.entities.ToolCallLog;
import ru.agimate.controlapi.database.entities.WebhookDeliveryLog;
import ru.agimate.controlapi.database.enums.RunStatus;
import ru.agimate.controlapi.database.projections.AgentRunProjection;
import ru.agimate.controlapi.database.projections.TriggerLogWithAgentsCountProjection;
import ru.agimate.controlapi.database.repositories.AgentRunRepository;
import ru.agimate.controlapi.database.repositories.AgentRunTurnRepository;
import ru.agimate.controlapi.database.repositories.ToolCallLogRepository;
import ru.agimate.controlapi.database.repositories.TriggerLogRepository;
import ru.agimate.controlapi.database.repositories.WebhookDeliveryLogRepository;
import ru.agimate.controlapi.service.session.AgentSessionService;
import ru.agimate.controlapi.service.trigger.RunCancellationService;

import java.util.LinkedHashMap;
import java.util.regex.Pattern;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Tools of the platform connector's observability module — runs, sessions and the log tables, plus
 * the run transcript (turns and the starting prompt). A thin adapter: every read goes through the
 * repositories (or the entity-returning {@link AgentSessionService}), every write through
 * {@link RunCancellationService}; nothing imports {@code controller/**}. Ownership is enforced the
 * same way the manage API enforces it — a foreign run/session reads as not found, never as
 * forbidden. Shared guards and parsing live in {@link PlatformToolsSupport}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlatformObservabilityToolService {

    /** The prompt snapshot is stored as a JSON array of messages — see {@code SavePrompt}. */
    private static final TypeReference<List<Object>> MESSAGES_TYPE = new TypeReference<>() {
    };

    /**
     * Result/argument fields that carry platform secrets and must never resurface through the
     * observability tools. The platform surface returns an agent key once ({@code create_agent},
     * {@code regenerate_agent_key}) and accepts provider keys and webhook auth headers as write-only
     * inputs; the raw tool output is persisted into {@code tool_call_logs.output} and the turn
     * ledger's {@code argumentsJson}/{@code outputJson} verbatim, so reads of history have to redact
     * them here — the caller who minted a key already got it in the operation's own result.
     */
    private static final Set<String> SECRET_KEYS = Set.of("plaintextKey", "apiKey", "webhookAuthHeader");

    /**
     * Keys as they appear in free text — a model quoting a tool result into its reply, which the
     * JSON-field redaction above cannot see. The formats are the platform's own ({@code AppKeyUtils}:
     * 4-char prefix + 60 base64url chars; provider keys conventionally {@code sk-}).
     */
    // Lookarounds instead of \b: '-' is not a word character, so a key ending in '-' (base64url
    // uses it) would have no word boundary after it and slip past \b untouched.
    private static final Pattern AGENT_KEY_PATTERN =
            Pattern.compile("(?<![A-Za-z0-9_-])(agnt|appk)[A-Za-z0-9_-]{60}(?![A-Za-z0-9_-])");
    private static final Pattern PROVIDER_KEY_PATTERN =
            Pattern.compile("(?<![A-Za-z0-9_-])sk-[A-Za-z0-9_-]{16,}(?![A-Za-z0-9_-])");

    private final AgentRunRepository agentRunRepository;
    private final AgentRunTurnRepository agentRunTurnRepository;
    private final AgentSessionService agentSessionService;
    private final ToolCallLogRepository toolCallLogRepository;
    private final TriggerLogRepository triggerLogRepository;
    private final WebhookDeliveryLogRepository webhookDeliveryLogRepository;
    private final RunCancellationService runCancellationService;

    // ---- runs -----------------------------------------------------------------------------

    @Tool(name = "list_runs",
            description = "List runs of your agents, newest first. Every filter is optional and they "
                    + "compose: agentId, sessionId, connectorCode, connectionId, name (substring of "
                    + "the trigger's name), status (ENQUEUED, RUNNING, DONE, FAILED, CANCELLED)",
            annotations = @ToolAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = false))
    public RunList listRuns(
            @ToolParam(value = "Filter by agent public ID", required = false) String agentId,
            @ToolParam(value = "Filter by channel session public ID", required = false) String sessionId,
            @ToolParam(value = "Filter by connector code", required = false) String connectorCode,
            @ToolParam(value = "Filter by connection id", required = false) String connectionId,
            @ToolParam(value = "Filter by trigger name (substring)", required = false) String name,
            @ToolParam(value = "Filter by status: ENQUEUED, RUNNING, DONE, FAILED, CANCELLED",
                    required = false) String status) {
        Page<AgentRunProjection> page = agentRunRepository.findRunsWithFilters(
                PlatformToolsSupport.userId(), null,
                PlatformToolsSupport.parseUuidOrNull(agentId, "agentId"),
                PlatformToolsSupport.parseUuidOrNull(sessionId, "sessionId"),
                null,
                PlatformToolsSupport.blankToNull(connectorCode),
                PlatformToolsSupport.blankToNull(connectionId),
                PlatformToolsSupport.blankToNull(name),
                PlatformToolsSupport.blankToNull(status) == null ? null
                        : PlatformToolsSupport.parseEnum(RunStatus.class, status, "status"),
                PageRequest.of(0, PlatformToolsSupport.MAX_LISTING, Sort.by("createdAt").descending()));
        List<RunBrief> runItems = page.getContent().stream()
                .map(this::toRunBrief)
                .toList();
        return new RunList(runItems, runItems.size() == PlatformToolsSupport.MAX_LISTING);
    }

    @Tool(name = "get_run",
            description = "Get one run by id — the same row the listing returns, narrowed to a key. "
                    + "Someone else's run reads as not found",
            annotations = @ToolAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = false))
    public RunBrief getRun(@ToolParam("Run public ID") String runId) {
        UUID id = PlatformToolsSupport.parseUuid(runId, "runId");
        return agentRunRepository.findRunsWithFilters(
                        PlatformToolsSupport.userId(), id, null, null, null, null, null, null, null,
                        PageRequest.of(0, 1))
                .getContent().stream().findFirst()
                .map(this::toRunBrief)
                .orElseThrow(() -> new ConnectorException("Run not found"));
    }

    @Tool(name = "cancel_run",
            description = "Ask a run to stop at its next seam; idempotent — a finished run is not an error",
            annotations = @ToolAnnotations(destructiveHint = true, openWorldHint = false))
    public CancelRunResult cancelRun(@ToolParam("Run public ID") String runId) {
        RunCancellationService.CancelResult result = PlatformToolsSupport.domain(() ->
                runCancellationService.cancelRun(PlatformToolsSupport.parseUuid(runId, "runId"),
                        PlatformToolsSupport.userId()));
        return new CancelRunResult(result.status().name(), result.requested(), result.alreadyFinished());
    }

    // ---- sessions ------------------------------------------------------------------------

    @Tool(name = "list_sessions",
            description = "List your channel sessions (conversations with agents), freshest activity "
                    + "first. Every filter is optional: agentId, channelId, connectorCode",
            annotations = @ToolAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = false))
    public SessionList listSessions(
            @ToolParam(value = "Filter by agent public ID", required = false) String agentId,
            @ToolParam(value = "Filter by channel public ID", required = false) String channelId,
            @ToolParam(value = "Filter by connector code", required = false) String connectorCode) {
        Page<AgentSession> page = agentSessionService.list(PlatformToolsSupport.userId(),
                PlatformToolsSupport.parseUuidOrNull(agentId, "agentId"),
                PlatformToolsSupport.parseUuidOrNull(channelId, "channelId"),
                PlatformToolsSupport.blankToNull(connectorCode),
                0, PlatformToolsSupport.MAX_LISTING);
        List<SessionBrief> sessionItems = page.getContent().stream()
                .map(this::toSessionBrief)
                .toList();
        return new SessionList(sessionItems, sessionItems.size() == PlatformToolsSupport.MAX_LISTING);
    }

    @Tool(name = "get_session",
            description = "Get one channel session by id — the conversation record runs write to, "
                    + "with its scope, connector and title. Someone else's session reads as not found",
            annotations = @ToolAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = false))
    public SessionBrief getSession(@ToolParam("Session public ID") String sessionId) {
        UUID id = PlatformToolsSupport.parseUuid(sessionId, "sessionId");
        AgentSession session;
        try {
            session = agentSessionService.getById(id);
        } catch (NotFoundStatusException e) {
            throw new ConnectorException("Session not found");
        }
        if (!session.getUserId().equals(PlatformToolsSupport.userId())) {
            throw new ConnectorException("Session not found");
        }
        return toSessionBrief(session);
    }

    @Tool(name = "cancel_session",
            description = "Stop every live run of a channel session (the running one and those still "
                    + "queued behind it)",
            annotations = @ToolAnnotations(destructiveHint = true, openWorldHint = false))
    public CancelSessionResult cancelSession(@ToolParam("Session public ID") String sessionId) {
        int cancelled = PlatformToolsSupport.domain(() -> runCancellationService.cancelSession(
                PlatformToolsSupport.parseUuid(sessionId, "sessionId"), PlatformToolsSupport.userId()));
        return new CancelSessionResult(sessionId, cancelled);
    }

    // ---- logs ----------------------------------------------------------------------------

    @Tool(name = "list_tool_call_logs",
            description = "List your tool call logs, newest first. Every filter is optional: agentId, "
                    + "connectorCode, connectionId, name (substring of the tool name), accessEffect "
                    + "(ALLOW or DENY), status (SUCCESS, ERROR, PENDING)",
            annotations = @ToolAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = false))
    public ToolCallLogList listToolCallLogs(
            @ToolParam(value = "Filter by agent public ID", required = false) String agentId,
            @ToolParam(value = "Filter by connector code", required = false) String connectorCode,
            @ToolParam(value = "Filter by connection id", required = false) String connectionId,
            @ToolParam(value = "Filter by tool name (substring)", required = false) String name,
            @ToolParam(value = "Filter by access effect: ALLOW or DENY", required = false) String accessEffect,
            @ToolParam(value = "Filter by status: SUCCESS, ERROR, PENDING", required = false) String status) {
        // A blank filter is "not given", like every other optional filter in the module.
        ToolCallLogStatus statusValue = PlatformToolsSupport.blankToNull(status) == null ? null
                : PlatformToolsSupport.parseEnum(ToolCallLogStatus.class, status, "status");
        AccessEffect effect = PlatformToolsSupport.blankToNull(accessEffect) == null ? null
                : PlatformToolsSupport.parseEnum(AccessEffect.class, accessEffect, "accessEffect");
        Page<ToolCallLog> page = toolCallLogRepository.findWithFilters(
                PlatformToolsSupport.userId(),
                PlatformToolsSupport.parseUuidOrNull(agentId, "agentId"),
                PlatformToolsSupport.blankToNull(connectorCode),
                PlatformToolsSupport.blankToNull(connectionId),
                effect,
                PlatformToolsSupport.blankToNull(name),
                statusValue == null ? null : statusValue.name(),
                PageRequest.of(0, PlatformToolsSupport.MAX_LISTING, Sort.by("createdAt").descending()));
        List<ToolCallLogItem> logItems = page.getContent().stream()
                .map(this::toToolCallLogItem)
                .toList();
        return new ToolCallLogList(logItems, logItems.size() == PlatformToolsSupport.MAX_LISTING);
    }

    @Tool(name = "list_trigger_logs",
            description = "List the inbound events (triggers) that reached your connectors, newest "
                    + "first, with how many of your agents handled each",
            annotations = @ToolAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = false))
    public TriggerLogList listTriggerLogs(
            @ToolParam(value = "Filter by connector code", required = false) String connectorCode) {
        Page<TriggerLogWithAgentsCountProjection> page = triggerLogRepository.findByUserIdWithFilters(
                PlatformToolsSupport.userId(), PlatformToolsSupport.blankToNull(connectorCode),
                PageRequest.of(0, PlatformToolsSupport.MAX_LISTING, Sort.by("occurredAt").descending()));
        List<TriggerLogItem> triggerItems = page.getContent().stream()
                .map(this::toTriggerLogItem)
                .toList();
        return new TriggerLogList(triggerItems, triggerItems.size() == PlatformToolsSupport.MAX_LISTING);
    }

    @Tool(name = "list_webhook_deliveries",
            description = "List the webhook deliveries to your WEBHOOK-type agents, newest first, "
                    + "with the response status, duration and error of each",
            annotations = @ToolAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = false))
    public WebhookDeliveryList listWebhookDeliveries(
            @ToolParam(value = "Filter by agent public ID", required = false) String agentId) {
        // "Newest first" is a promise, so the order must be explicit — the repository returns rows
        // in insertion order otherwise, and the cap would make the window arbitrary. A blank filter
        // is the same as none, like every other listing.
        UUID agent = PlatformToolsSupport.parseUuidOrNull(agentId, "agentId");
        Page<WebhookDeliveryLog> page = agent == null
                ? webhookDeliveryLogRepository.findByUserId(PlatformToolsSupport.userId(),
                        PageRequest.of(0, PlatformToolsSupport.MAX_LISTING,
                                Sort.by("deliveredAt").descending()))
                : webhookDeliveryLogRepository.findByUserIdAndAgentId(PlatformToolsSupport.userId(),
                        agent,
                        PageRequest.of(0, PlatformToolsSupport.MAX_LISTING,
                                Sort.by("deliveredAt").descending()));
        List<WebhookDeliveryItem> deliveryItems = page.getContent().stream()
                .map(this::toWebhookDeliveryItem)
                .toList();
        return new WebhookDeliveryList(deliveryItems, deliveryItems.size() == PlatformToolsSupport.MAX_LISTING);
    }

    // ---- run transcript ------------------------------------------------------------------

    @Tool(name = "get_run_turns",
            description = "The run's full transcript from the turn ledger, oldest turn first. Content "
                    + "is uncapped — tool outputs and reasoning come in full; use it to audit what "
                    + "actually happened",
            annotations = @ToolAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = false))
    public RunTurnList getRunTurns(@ToolParam("Run public ID") String runId) {
        AgentRunProjection run = ownedRun(runId);
        List<RunTurnItem> turns = agentRunTurnRepository.findByRunIdOrderByTurnIndexAsc(run.getId())
                .stream().map(this::toRunTurnItem).toList();
        return new RunTurnList(turns);
    }

    @Tool(name = "get_run_prompt",
            description = "The message list exactly as it went into the first LLM call: system "
                    + "blocks, session history and the trigger's turn. Not available for runs that "
                    + "never reached the loop",
            annotations = @ToolAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = false))
    public RunPrompt getRunPrompt(@ToolParam("Run public ID") String runId) {
        AgentRunProjection run = ownedRun(runId);
        AgentRun entity = agentRunRepository.findById(run.getId())
                .orElseThrow(() -> new ConnectorException("Run not found: " + runId));
        return new RunPrompt(run.getId().toString(), promptToMap(entity.getPrompt()));
    }

    // ---- helpers -------------------------------------------------------------------------

    /**
     * The ownership gate shared with the manage API's {@code getRun}: the run's trigger log must
     * belong to the caller, and a foreign id reads as not found.
     */
    private AgentRunProjection ownedRun(String runId) {
        UUID id = PlatformToolsSupport.parseUuid(runId, "runId");
        return agentRunRepository.findRunsWithFilters(
                        PlatformToolsSupport.userId(), id, null, null, null, null, null, null, null,
                        PageRequest.of(0, 1))
                .getContent().stream().findFirst()
                .orElseThrow(() -> new ConnectorException("Run not found: " + runId));
    }

    private RunBrief toRunBrief(AgentRunProjection run) {
        // The run's result is the agent's final answer — the same text the turn ledger carries, so
        // keys quoted into it are redacted the same way (a run that created an agent would otherwise
        // hand the plaintext key straight out of the listing).
        return new RunBrief(run.getId().toString(), run.getName(), run.getConnectorCode(),
                run.getConnectionId(), run.getStatus().name(), redactKeys(run.getResult()),
                redactKeys(run.getError()),
                run.getSessionId() == null ? null : run.getSessionId().toString(),
                run.getMainRunId() == null ? null : run.getMainRunId().toString(),
                run.getSteeredAt() != null, run.getTurnsIntact(), run.getTurnsCount(),
                run.getLastActivityAt() == null ? null : run.getLastActivityAt().toString());
    }

    private SessionBrief toSessionBrief(AgentSession session) {
        return new SessionBrief(session.getId().toString(), session.getScope().name(),
                session.getAgentId().toString(), session.getConnectorCode(),
                session.getConnectionId().toString(), session.getTitle(),
                session.getLastActivityAt() == null ? null : session.getLastActivityAt().toString(),
                session.getClosedAt() == null ? null : session.getClosedAt().toString());
    }

    private ToolCallLogItem toToolCallLogItem(ToolCallLog log) {
        return new ToolCallLogItem(log.getId().toString(), log.getAgentId().toString(),
                log.getConnectorCode(), log.getConnectionId(), log.getName(),
                log.getAccessEffect() == null ? null : log.getAccessEffect().name(),
                statusOf(log),
                log.getRunId() == null ? null : log.getRunId().toString(),
                log.getExternalId(),
                log.getCreatedAt() == null ? null : log.getCreatedAt().toString(),
                log.getFinishAt() == null ? null : log.getFinishAt().toString(),
                redactKeys(log.getError()), redactKeys(redactSecrets(log.getOutput())));
    }

    /**
     * Execution status of a log row, derived from {@code finishAt}/{@code error} exactly as the
     * manage API derives its {@code ToolCallStatus}: SUCCESS — finished without an error; ERROR —
     * finished with an error; PENDING — nothing finished (a refused DENY row has no finish stamp and
     * reads as pending too).
     */
    private static String statusOf(ToolCallLog log) {
        // The same derivation as the repository filter: ERROR is "an error is recorded" (a refused
        // DENY row has error set but never finishes), PENDING is "nothing finished and nothing
        // failed" — a row shown as ERROR must be found by the ERROR filter.
        if (log.getError() != null) {
            return "ERROR";
        }
        if (log.getFinishAt() != null) {
            return "SUCCESS";
        }
        return "PENDING";
    }

    private TriggerLogItem toTriggerLogItem(TriggerLogWithAgentsCountProjection trigger) {
        // The inbound payload is user content — a provider apiKey pasted into a conversation is a
        // trigger payload, so it gets the same deep redaction as every other read in the module.
        @SuppressWarnings("unchecked")
        Map<String, Object> input = (Map<String, Object>) redactDeep(trigger.getInput());
        return new TriggerLogItem(trigger.getId().toString(), trigger.getConnectorCode(),
                trigger.getConnectionId(), trigger.getExternalId(), trigger.getName(),
                trigger.getOccurredAt() == null ? null : trigger.getOccurredAt().toString(),
                trigger.getAgentsCount(), input);
    }

    private WebhookDeliveryItem toWebhookDeliveryItem(WebhookDeliveryLog delivery) {
        return new WebhookDeliveryItem(delivery.getId().toString(),
                delivery.getAgentRun().getId().toString(), delivery.getRequestUrl(),
                delivery.getResponseStatusCode(), redactKeys(delivery.getError()), delivery.getDurationMs(),
                delivery.getDeliveredAt() == null ? null : delivery.getDeliveredAt().toString(),
                delivery.isSuccess());
    }

    /**
     * A turn flattened to a single tool: the first tool call of an ASSISTANT turn (its
     * {@code name}/{@code argumentsJson}) and the first tool result of a TOOL turn (its
     * {@code outputJson}, reported as {@code toolError} when the result {@code failed}).
     */
    private RunTurnItem toRunTurnItem(AgentRunTurn turn) {
        String toolName = null;
        String toolInput = null;
        String toolOutput = null;
        String toolError = null;
        if (turn.getToolCalls() != null && !turn.getToolCalls().isEmpty()) {
            Map<String, Object> call = turn.getToolCalls().get(0);
            toolName = string(call, "name");
            toolInput = string(call, "argumentsJson");
        }
        if (turn.getToolResults() != null && !turn.getToolResults().isEmpty()) {
            Map<String, Object> result = turn.getToolResults().get(0);
            if (toolName == null) {
                toolName = string(result, "name");
            }
            String output = string(result, "outputJson");
            if (Boolean.TRUE.equals(result.get("failed"))) {
                toolError = output;
            } else {
                toolOutput = output;
            }
        }
        return new RunTurnItem(turn.getTurnIndex(), turn.getRole().name(), redactKeys(turn.getText()),
                toolName, redactKeys(redactSecrets(toolInput)), redactKeys(redactSecrets(toolOutput)),
                redactKeys(redactSecrets(toolError)),
                turn.getCreatedAt() == null ? null : turn.getCreatedAt().toString(),
                redactToolPayloads(turn.getToolCalls(), "argumentsJson"),
                redactToolPayloads(turn.getToolResults(), "outputJson"));
    }

    /**
     * The turn's full call/result lists with secret-bearing payload strings redacted — the flattened
     * fields above cover the first element, the lists cover every element.
     */
    private static List<Map<String, Object>> redactToolPayloads(List<Map<String, Object>> entries, String payloadKey) {
        if (entries == null) {
            return null;
        }
        return entries.stream()
                .map(entry -> {
                    Map<String, Object> copy = new LinkedHashMap<>(entry);
                    Object payload = copy.get(payloadKey);
                    if (payload instanceof String json) {
                        copy.put(payloadKey, redactKeys(redactSecrets(json)));
                    }
                    return copy;
                })
                .toList();
    }

    /** Redacts platform keys from free text; non-matching text passes through unchanged. */
    private static String redactKeys(String text) {
        if (text == null) {
            return null;
        }
        String redacted = AGENT_KEY_PATTERN.matcher(text).replaceAll("[key redacted]");
        return PROVIDER_KEY_PATTERN.matcher(redacted).replaceAll("[key redacted]");
    }


    /**
     * Removes {@link #SECRET_KEYS} from a JSON object document; anything that is not a JSON object
     * (non-JSON text, arrays, null) passes through untouched. The redaction is key-name based and
     * applies to every connector alike — a platform secret that ever lands in a log or a turn is
     * covered even if the tool that produced it is not known here.
     */
    private static String redactSecrets(String json) {
        if (json == null || json.isBlank()) {
            return json;
        }
        try {
            JsonNode node = JsonUtils.MAPPER.readTree(json);
            boolean removed;
            if (node.isArray()) {
                removed = false;
                for (JsonNode item : node) {
                    if (item.isObject()) {
                        removed |= redactNode((ObjectNode) item);
                    }
                }
            } else if (node.isObject()) {
                removed = redactNode((ObjectNode) node);
            } else {
                return json;
            }
            return removed ? JsonUtils.MAPPER.writeValueAsString(node) : json;
        } catch (Exception e) {
            // Not JSON (or not parseable) — there is nothing structured to redact.
            return json;
        }
    }

    /**
     * Removes {@link #SECRET_KEYS} at every depth: a secret nested under a result object would
     * otherwise survive the top-level pass.
     *
     * @return whether any key was removed
     */
    private static boolean redactNode(ObjectNode node) {
        boolean removed = false;
        for (String key : SECRET_KEYS) {
            removed |= node.remove(key) != null;
        }
        var fields = node.fields();
        while (fields.hasNext()) {
            JsonNode value = fields.next().getValue();
            if (value.isObject()) {
                removed |= redactNode((ObjectNode) value);
            } else if (value.isArray()) {
                for (JsonNode item : value) {
                    if (item.isObject()) {
                        removed |= redactNode((ObjectNode) item);
                    }
                }
            }
        }
        return removed;
    }

    private static String string(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? null : value.toString();
    }

    /**
     * The run's input snapshot as a plain map. The stored tree is a JSON array of messages (the
     * worker's {@code SavePrompt}); the array form is wrapped under {@code messages} so the record's
     * map stays total. An object tree (not produced today) converts as-is.
     */
    private static Map<String, Object> promptToMap(JsonNode prompt) {
        if (prompt == null) {
            return Map.of();
        }
        // The snapshot carries the session history — earlier assistant answers that may quote keys.
        // Both stored shapes (a bare message array and an object) are walked deeply: any string
        // value matching a key pattern is redacted, wherever it sits in the tree.
        if (prompt.isArray()) {
            List<?> raw = JsonUtils.MAPPER.convertValue(prompt, MESSAGES_TYPE);
            return Map.of("messages", raw.stream().map(PlatformObservabilityToolService::redactDeep).toList());
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> converted = (Map<String, Object>) JsonUtils.MAPPER
                .convertValue(prompt, JsonUtils.MAP_TYPE_REFERENCE);
        return (Map<String, Object>) redactDeep(converted);
    }

    /** Deep redaction of one prompt node: strings through {@link #redactKeys}, maps/lists recursed. */
    @SuppressWarnings("unchecked")
    private static Object redactDeep(Object node) {
        if (node instanceof String text) {
            return redactKeys(text);
        }
        if (node instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            map.forEach((key, value) -> {
                // Field-name removal too: a trigger payload keyed literally "apiKey" must not
                // survive even when the value does not match any key pattern.
                if (SECRET_KEYS.contains(key)) {
                    return;
                }
                copy.put(key, redactDeep(value));
            });
            return copy;
        }
        if (node instanceof List<?> list) {
            return list.stream().map(PlatformObservabilityToolService::redactDeep).toList();
        }
        return node;
    }

    /**
     * The allowed status values of {@code list_tool_call_logs}. The repository filters on these
     * strings; the manage API's {@code ToolCallStatus} enum is a controller type and stays out of
     * the connector layer — hence this module-private mirror, used only to validate the param with
     * the standard allowed-values message.
     */
    private enum ToolCallLogStatus {
        SUCCESS,
        ERROR,
        PENDING
    }
}
