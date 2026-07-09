package ru.agimate.agentworker.workers;

import com.google.protobuf.ByteString;
import dev.dbos.transact.DBOS;
import dev.dbos.transact.workflow.Workflow;
import dev.dbos.transact.workflow.WorkflowClassName;
import lombok.extern.slf4j.Slf4j;
import ru.agimate.agentworker.GetToolResultResponse;
import ru.agimate.agentworker.ToolResultStatus;
import ru.agimate.agentworker.grpc.AgentWorkerClient;

import java.nio.charset.StandardCharsets;

/**
 * Tool worker: one backend tool call per queue item. Issues {@code ExecuteToolAsync} and polls
 * {@code GetToolResult} inside a single durable step. The workflow never raises — failures come
 * back in {@link Outcome#error()} so DBOS does not log them as workflow exceptions. The
 * {@code toolCallId} arrives as a workflow argument (identical across replays), so replays issue
 * the same {@code ExecuteToolAsync} and poll the same id.
 */
@Slf4j
@WorkflowClassName(Queues.TOOL_CLASS)
public class ToolCallWorkflowImpl implements ToolCallWorkflow {

    private static final long POLL_INTERVAL_MS = 500;
    private static final long POLL_TIMEOUT_MS = 60_000;

    private final AgentWorkerClient client;
    private final DBOS dbos;

    public ToolCallWorkflowImpl(AgentWorkerClient client, DBOS dbos) {
        this.client = client;
        this.dbos = dbos;
    }

    @Override
    @Workflow(name = Queues.TOOL_WORKFLOW)
    public Outcome toolCall(String connectorCode, String backendName, String argsJson,
                                    String toolCallId, String agentId, String agentSessionId, String connectionId) {
        try {
            String outputJson = dbos.runStep(
                    () -> callConnectorTool(connectorCode, backendName, argsJson, toolCallId, connectionId, agentId, agentSessionId),
                    "call_connector_tool");
            return Outcome.ok(outputJson);
        } catch (Exception e) {
            log.warn("tool {} (connector={}) failed: {}", backendName, connectorCode, e.getMessage());
            String msg = e.getMessage();
            return Outcome.error(msg != null && !msg.isBlank() ? msg : e.getClass().getSimpleName());
        }
    }

    private String callConnectorTool(String connectorCode, String toolName, String argsJson,
                                     String toolCallId, String connectionId, String agentId, String agentSessionId) {
        client.executeToolAsync(toolCallId, connectorCode, connectionId, toolName,
                argsJson.getBytes(StandardCharsets.UTF_8), agentId, agentSessionId);
        long deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS;
        while (true) {
            GetToolResultResponse result = client.getToolResult(agentId, toolCallId);
            if (result.getStatus() == ToolResultStatus.TOOL_RESULT_STATUS_SUCCESS) {
                ByteString out = result.getOutputJson();
                return out.isEmpty() ? "" : out.toStringUtf8();
            }
            if (result.getStatus() == ToolResultStatus.TOOL_RESULT_STATUS_ERROR) {
                String err = result.getError();
                throw new IllegalStateException("tool " + toolName + " failed: "
                        + (err.isBlank() ? "no error message" : err));
            }
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException("tool " + toolName + " (id=" + toolCallId + ") timed out after "
                        + (POLL_TIMEOUT_MS / 1000) + "s");
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while polling tool " + toolName, ie);
            }
        }
    }
}
