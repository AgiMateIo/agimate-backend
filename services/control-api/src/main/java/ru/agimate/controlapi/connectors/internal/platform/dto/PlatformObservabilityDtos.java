package ru.agimate.controlapi.connectors.internal.platform.dto;

import java.util.List;
import java.util.Map;

/**
 * View models of the observability tools of the platform connector
 * ({@code PlatformObservabilityToolService}): runs, sessions, tool-call/trigger/webhook-delivery logs and
 * run transcripts. Flat and LLM-friendly (public ids as strings). See {@link PlatformDtos} for the
 * shared-file rules; this file holds only the records the observability module owns.
 */
public final class PlatformObservabilityDtos {

    private PlatformObservabilityDtos() {
    }

    public record RunList(List<RunBrief> items, boolean truncated) {
    }

    /**
     * A run of an agent plus the trigger event that produced it.
     *
     * @param triggerName   the trigger's name (substring-searchable in {@code list_runs})
     * @param steered       {@code true} when another run of the session absorbed and answered this
     *                      run's message
     * @param turnsIntact   whether the run's turn ledger can be replayed — {@code false} means the
     *                      transcript has a hole
     * @param turnsCount    how many turns the run recorded
     */
    public record RunBrief(String id, String triggerName, String connectorCode, String connectionId,
                           String status, String result, String error, String sessionId, String mainRunId,
                           boolean steered, boolean turnsIntact, long turnsCount, String lastActivityAt) {
    }

    /**
     * @param status          the run's status when the request landed (the terminal one arrives later)
     * @param requested       whether this call recorded the request — {@code false} for a repeat press
     *                        as well as for a finished run
     * @param alreadyFinished the run finished on its own first — cancelling what already happened
     *                        is a no-op, not an error
     */
    public record CancelRunResult(String status, boolean requested, boolean alreadyFinished) {
    }

    public record RunTurnList(List<RunTurnItem> items) {
    }

    /**
     * One turn of a run, flattened to a single tool per row: the first tool call of an ASSISTANT
     * turn carries {@code toolName}/{@code toolInput}, the first tool result of a TOOL turn carries
     * {@code toolOutput} (or {@code toolError} when the result {@code failed}).
     *
     * @param content the turn's text: the request on USER, the answer or the preamble before tool
     *                calls on ASSISTANT, null on TOOL
     */
    /**
     * One turn of the transcript. {@code toolName}/{@code toolInput}/{@code toolOutput}/{@code toolError}
     * are the flattened view of the turn's first tool call and first tool result (the common case);
     * {@code toolCalls}/{@code toolResults} carry every call/result of the turn, with secret fields
     * ({@code apiKey}, {@code plaintextKey}, {@code webhookAuthHeader}) redacted from their
     * {@code argumentsJson}/{@code outputJson} payloads.
     */
    public record RunTurnItem(int turnIndex, String role, String content, String toolName,
                              String toolInput, String toolOutput, String toolError, String createdAt,
                              List<Map<String, Object>> toolCalls, List<Map<String, Object>> toolResults) {
    }

    /**
     * @param prompt the run's input snapshot as a map — the message list exactly as it went into the
     *               first LLM call. Empty when the snapshot was never taken (the run did not reach
     *               the loop, or it predates the feature). The stored snapshot is a JSON array of
     *               messages, so the array form is wrapped under the key {@code messages}
     */
    public record RunPrompt(String runId, Map<String, Object> prompt) {
    }

    public record SessionList(List<SessionBrief> items, boolean truncated) {
    }

    /** A channel session — the conversation a user is having with an agent. */
    public record SessionBrief(String id, String scope, String agentId, String connectorCode,
                               String connectionId, String title, String lastActivityAt, String closedAt) {
    }

    /**
     * @param sessionId the session the request was recorded for
     * @param cancelled how many live runs the request was recorded for
     */
    public record CancelSessionResult(String sessionId, int cancelled) {
    }

    public record ToolCallLogList(List<ToolCallLogItem> items, boolean truncated) {
    }

    /**
     * One tool-use log row. {@code status} is derived from {@code finishAt}/{@code error}: PENDING —
     * no finish and no error; SUCCESS — finished without an error; ERROR — finished with an error.
     */
    public record ToolCallLogItem(String id, String agentId, String connectorCode, String connectionId,
                                  String name, String accessEffect, String status, String runId,
                                  String externalId, String createdAt, String finishAt, String error,
                                  String output) {
    }

    public record TriggerLogList(List<TriggerLogItem> items, boolean truncated) {
    }

    /**
     * One inbound trigger event.
     *
     * @param agentsCount how many of the user's agents handled the event
     * @param input       the trigger's payload
     */
    public record TriggerLogItem(String id, String connectorCode, String connectionId, String externalId,
                                 String name, String occurredAt, long agentsCount, Map<String, Object> input) {
    }

    public record WebhookDeliveryList(List<WebhookDeliveryItem> items, boolean truncated) {
    }

    /**
     * One webhook delivery to a WEBHOOK-type agent.
     *
     * @param success whether the delivery was answered with a 2xx status
     */
    public record WebhookDeliveryItem(String id, String runId, String requestUrl,
                                      Integer responseStatusCode, String error, Long durationMs,
                                      String deliveredAt, boolean success) {
    }
}
