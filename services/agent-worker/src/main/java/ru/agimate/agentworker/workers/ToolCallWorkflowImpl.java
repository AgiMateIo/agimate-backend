package ru.agimate.agentworker.workers;

import com.google.protobuf.ByteString;
import dev.dbos.transact.DBOS;
import dev.dbos.transact.workflow.Workflow;
import dev.dbos.transact.workflow.WorkflowClassName;
import lombok.extern.slf4j.Slf4j;
import ru.agimate.agentworker.GetToolResultResponse;
import ru.agimate.agentworker.ToolResultStatus;
import ru.agimate.agentworker.config.AgentProperties;
import ru.agimate.agentworker.grpc.AgentWorkerClient;

import java.nio.charset.StandardCharsets;

/**
 * Tool worker: one backend tool call per queue item. Issues {@code ExecuteToolAsync} and polls
 * {@code GetToolResult} inside a single durable step. The workflow never raises — failures come
 * back in {@link Outcome#error()} so DBOS does not log them as workflow exceptions. The
 * {@code toolCallId} arrives as a workflow argument (identical across replays), so replays issue
 * the same {@code ExecuteToolAsync} and poll the same id.
 *
 * <p>The poll budget bounds waiting only — it does not cancel the backend job, so a timed-out
 * tool may still complete and apply its effects. The timeout message says so explicitly: the
 * model must check state before retrying a non-idempotent tool. The budget is per-tool: the
 * spec's {@code timeout_seconds} (clamped to {@value #MAX_TIMEOUT_SECONDS}s) when declared,
 * otherwise {@code agent.tool.poll-timeout}.
 */
@Slf4j
@WorkflowClassName(Queues.TOOL_CLASS)
public class ToolCallWorkflowImpl implements ToolCallWorkflow {

    private static final long POLL_INTERVAL_MS = 500;
    /** После первой минуты ожидания поллим реже: долгие тулы не заслуживают 2 rps на gRPC. */
    private static final long SLOW_POLL_INTERVAL_MS = 2_000;
    private static final long SLOW_POLL_AFTER_MS = 60_000;
    /** Потолок заявленного спеком бюджета — 30 минут. */
    static final int MAX_TIMEOUT_SECONDS = 1800;

    private final AgentWorkerClient client;
    private final DBOS dbos;
    private final long pollTimeoutMs;
    private final int maxOutputChars;

    public ToolCallWorkflowImpl(AgentWorkerClient client, DBOS dbos, AgentProperties.Tool tool) {
        this.client = client;
        this.dbos = dbos;
        this.pollTimeoutMs = tool.getPollTimeout().toMillis();
        this.maxOutputChars = tool.getMaxOutputChars();
    }

    @Override
    @Workflow(name = Queues.TOOL_WORKFLOW)
    public Outcome toolCall(String connectorCode, String backendName, String argsJson,
                                    String toolCallId, String agentId, String triggerId, String connectionId,
                                    int timeoutSeconds) {
        try {
            String outputJson = dbos.runStep(
                    () -> callConnectorTool(connectorCode, backendName, argsJson, toolCallId, connectionId,
                            agentId, triggerId, effectiveTimeoutMs(timeoutSeconds, pollTimeoutMs)),
                    "call_connector_tool");
            return Outcome.ok(outputJson);
        } catch (Exception e) {
            log.warn("tool {} (connector={}) failed: {}", backendName, connectorCode, e.getMessage());
            String msg = e.getMessage();
            return Outcome.error(msg != null && !msg.isBlank() ? msg : e.getClass().getSimpleName());
        }
    }

    /** Бюджет ожидания: заявленный спеком (кламп 30 мин) либо дефолт воркера. */
    static long effectiveTimeoutMs(int specTimeoutSeconds, long defaultMs) {
        if (specTimeoutSeconds <= 0) {
            return defaultMs;
        }
        return Math.min(specTimeoutSeconds, MAX_TIMEOUT_SECONDS) * 1000L;
    }

    private String callConnectorTool(String connectorCode, String toolName, String argsJson,
                                     String toolCallId, String connectionId, String agentId, String triggerId,
                                     long budgetMs) {
        client.executeToolAsync(toolCallId, connectorCode, connectionId, toolName,
                argsJson.getBytes(StandardCharsets.UTF_8), agentId, triggerId);
        long start = System.currentTimeMillis();
        long deadline = start + budgetMs;
        while (true) {
            GetToolResultResponse result = client.getToolResult(agentId, toolCallId, triggerId);
            if (result.getStatus() == ToolResultStatus.TOOL_RESULT_STATUS_SUCCESS) {
                ByteString out = result.getOutputJson();
                return out.isEmpty() ? "" : truncateOutput(out.toStringUtf8(), maxOutputChars);
            }
            if (result.getStatus() == ToolResultStatus.TOOL_RESULT_STATUS_ERROR) {
                String err = result.getError();
                throw new IllegalStateException("tool " + toolName + " failed: "
                        + (err.isBlank() ? "no error message" : err));
            }
            long now = System.currentTimeMillis();
            if (now > deadline) {
                throw new IllegalStateException("tool " + toolName + " (id=" + toolCallId
                        + ") did not finish within " + (budgetMs / 1000) + "s; the call was NOT"
                        + " cancelled and may still complete with its effects applied — verify the"
                        + " current state before retrying");
            }
            try {
                Thread.sleep(now - start < SLOW_POLL_AFTER_MS ? POLL_INTERVAL_MS : SLOW_POLL_INTERVAL_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while polling tool " + toolName, ie);
            }
        }
    }

    /**
     * Cut a giant tool output down to {@code maxChars} with an explicit marker: the output rides
     * in the model context of every following turn and in each {@code llm_call} checkpoint, so an
     * unbounded one (a wide SELECT, a dumped file) inflates the whole rest of the run. Truncated
     * inside the durable step — the checkpointed outcome is already bounded. The cut result is no
     * longer valid JSON; the model reads it as text, the marker says what happened.
     */
    static String truncateOutput(String output, int maxChars) {
        if (output.length() <= maxChars) {
            return output;
        }
        int cut = maxChars;
        // Не рвать суррогатную пару UTF-16 посередине.
        if (Character.isHighSurrogate(output.charAt(cut - 1))) {
            cut--;
        }
        return output.substring(0, cut)
                + "\n…[tool output truncated by worker: " + output.length()
                + " chars total, first " + cut + " shown]";
    }
}
