package ru.agimate.agentworker.grpc;

import com.google.protobuf.ByteString;
import io.grpc.Channel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.agimate.agentworker.AgentContextGrpc;
import ru.agimate.agentworker.ClaimSteeringRequest;
import ru.agimate.agentworker.ClaimSteeringResponse;
import ru.agimate.agentworker.DetachToolRequest;
import ru.agimate.agentworker.DetachToolResponse;
import ru.agimate.agentworker.ExecuteToolAsyncAck;
import ru.agimate.agentworker.MarkSteeredRequest;
import ru.agimate.agentworker.MarkSteeredResponse;
import ru.agimate.agentworker.ExecuteToolRequest;
import ru.agimate.agentworker.FileChunk;
import ru.agimate.agentworker.GetFileRequest;
import ru.agimate.agentworker.GetLlmCredentialsRequest;
import ru.agimate.agentworker.GetRunContextRequest;
import ru.agimate.agentworker.RunContext;
import ru.agimate.agentworker.GetToolResultRequest;
import ru.agimate.agentworker.GetToolResultResponse;
import ru.agimate.agentworker.LlmCredentials;
import ru.agimate.agentworker.MessageKind;
import ru.agimate.agentworker.ToolTurn;
import ru.agimate.agentworker.MessageLogGrpc;
import ru.agimate.agentworker.ProgressType;
import ru.agimate.agentworker.ReportLlmUsageRequest;
import ru.agimate.agentworker.ReportLlmUsageResponse;
import ru.agimate.agentworker.SaveMessageRequest;
import ru.agimate.agentworker.SaveMessageResponse;
import ru.agimate.agentworker.SavePromptRequest;
import ru.agimate.agentworker.SavePromptResponse;
import ru.agimate.agentworker.SaveTurnRequest;
import ru.agimate.agentworker.SaveTurnResponse;
import ru.agimate.agentworker.SendMessageRequest;
import ru.agimate.agentworker.SendMessageResponse;
import ru.agimate.agentworker.ToolCallRec;
import ru.agimate.agentworker.ToolResultRec;
import ru.agimate.agentworker.TurnRole;
import ru.agimate.agentworker.ToolGatewayGrpc;
import ru.agimate.agentworker.WorkerControlGrpc;
import ru.agimate.agentworker.WorkerMessageType;
import ru.agimate.agentworker.config.AgentProperties;

import java.io.ByteArrayOutputStream;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Blocking gRPC facade over control-api's worker services. Exposes only the RPCs the worker
 * actually uses, returning the generated protobuf messages directly; a per-call deadline is
 * applied to every unary RPC.
 *
 * <p>{@code getToolResult} is polled by the tool worker and intentionally carries no
 * deadline of its own — its polling budget is enforced by the caller.
 *
 * <p>Failures surface as {@link ControlApiCallException}, never as the raw
 * {@link StatusRuntimeException} — DBOS java-serializes workflow/step failures and the gRPC
 * exception is not serializable (see the wrapper's javadoc).
 */
@Component
@Slf4j
public class AgentWorkerClient {

    private final AgentProperties props;
    private final AgentContextGrpc.AgentContextBlockingStub agentContext;
    private final MessageLogGrpc.MessageLogBlockingStub messageLog;
    private final ToolGatewayGrpc.ToolGatewayBlockingStub tools;
    private final WorkerControlGrpc.WorkerControlBlockingStub workerControl;

    public AgentWorkerClient(Channel controlApiAuthedChannel, AgentProperties props) {
        this.props = props;
        this.agentContext = AgentContextGrpc.newBlockingStub(controlApiAuthedChannel);
        this.messageLog = MessageLogGrpc.newBlockingStub(controlApiAuthedChannel);
        this.tools = ToolGatewayGrpc.newBlockingStub(controlApiAuthedChannel);
        this.workerControl = WorkerControlGrpc.newBlockingStub(controlApiAuthedChannel);
    }

    private long timeoutMs() {
        return props.getGrpc().getRequestTimeout().toMillis();
    }

    private AgentContextGrpc.AgentContextBlockingStub ctx() {
        return agentContext.withDeadlineAfter(timeoutMs(), TimeUnit.MILLISECONDS);
    }

    /**
     * Transport-level retry budget for UNAVAILABLE: covers a routine control-api restart
     * (sleeps 1+2+4+8+16+32 ≈ 63s total) so an in-flight run waits it out instead of dying.
     */
    private static final int UNAVAILABLE_MAX_ATTEMPTS = 7;
    private static final long UNAVAILABLE_INITIAL_BACKOFF_MS = 1_000;

    /**
     * Runs one RPC, converting {@link StatusRuntimeException} into the serializable
     * {@link ControlApiCallException}. ABORTED is an expected outcome (busy active-run claim), so
     * it logs quietly and never retries; UNAVAILABLE (control-api restarting/unreachable) retries
     * with backoff up to {@link #UNAVAILABLE_MAX_ATTEMPTS}; everything else logs the full chain
     * here because the wrapper drops the cause on purpose.
     */
    private static <T> T call(String rpc, Supplier<T> op) {
        long backoffMs = UNAVAILABLE_INITIAL_BACKOFF_MS;
        for (int attempt = 1; ; attempt++) {
            try {
                return op.get();
            } catch (StatusRuntimeException e) {
                Status.Code code = e.getStatus().getCode();
                // Expected outcomes rather than infra failures: ABORTED (an active-run claim already taken) and
                // RESOURCE_EXHAUSTED (the LLM quota is spent) — quietly and without a stack trace, never retried.
                if (code == Status.Code.ABORTED || code == Status.Code.RESOURCE_EXHAUSTED) {
                    log.debug("control-api RPC {}: {}", rpc, e.getMessage());
                    throw new ControlApiCallException(rpc, e.getStatus());
                }
                if (code == Status.Code.UNAVAILABLE && attempt < UNAVAILABLE_MAX_ATTEMPTS) {
                    log.info("control-api RPC {} unavailable (attempt {}/{}), retrying in {} ms",
                            rpc, attempt, UNAVAILABLE_MAX_ATTEMPTS, backoffMs);
                    try {
                        Thread.sleep(backoffMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new ControlApiCallException(rpc, e.getStatus());
                    }
                    backoffMs *= 2;
                    continue;
                }
                log.warn("control-api RPC {} failed", rpc, e);
                throw new ControlApiCallException(rpc, e.getStatus());
            }
        }
    }

    // ---- AgentContext ----------------------------------------------------------------

    /** The whole run context in one call: ordered prompt blocks plus scoped tools. */
    public RunContext getRunContext(String agentId, String runId) {
        return call("GetRunContext", () -> ctx().getRunContext(GetRunContextRequest.newBuilder()
                .setAgentId(agentId).setRunId(runId).build()));
    }

    public LlmCredentials getLlmCredentials(String agentId) {
        return call("GetLlmCredentials", () -> ctx().getLlmCredentials(GetLlmCredentialsRequest.newBuilder()
                .setAgentId(agentId).build()));
    }

    /**
     * Steering claim at the loop seam: atomically takes the session's younger queued messages to
     * absorb. Best-effort at the call site and never a durable step; idempotent for the same run.
     */
    public ClaimSteeringResponse claimSteering(String agentId, String runId) {
        return call("ClaimSteering", () -> ctx().claimSteering(ClaimSteeringRequest.newBuilder()
                .setAgentId(agentId).setRunId(runId).build()));
    }

    /** Confirms the model has seen the claimed messages; idempotent (stamped rows stay stamped). */
    public MarkSteeredResponse markSteered(String agentId, String runId, List<String> steeredRunIds) {
        return call("MarkSteered", () -> ctx().markSteered(MarkSteeredRequest.newBuilder()
                .setAgentId(agentId).setRunId(runId).addAllSteeredRunIds(steeredRunIds).build()));
    }

    /** Deadline for the file content stream — longer than the unary timeout: a file can be large. */
    private static final long FILE_STREAM_DEADLINE_MS = 60_000;
    /** Ceiling on a file assembled in memory — protection against an endless or inflated stream. */
    private static final int FILE_MAX_BYTES = 32 * 1024 * 1024;

    /**
     * Contents of an inbound attachment (server-streaming chunks) as a {@code byte[]}. Pulled inline
     * during the LLM call — like {@link #getLlmCredentials}, outside any DBOS checkpoint. An
     * oversized stream is aborted with {@code OUT_OF_RANGE}.
     */
    public byte[] getFile(String fileId, String agentId) {
        return call("GetFile", () -> {
            Iterator<FileChunk> chunks = agentContext
                    .withDeadlineAfter(FILE_STREAM_DEADLINE_MS, TimeUnit.MILLISECONDS)
                    .getFile(GetFileRequest.newBuilder().setFileId(fileId).setAgentId(agentId).build());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            while (chunks.hasNext()) {
                byte[] data = chunks.next().getData().toByteArray();
                if ((long) out.size() + data.length > FILE_MAX_BYTES) {
                    throw Status.OUT_OF_RANGE
                            .withDescription("file exceeds " + FILE_MAX_BYTES + " bytes")
                            .asRuntimeException();
                }
                out.writeBytes(data);
            }
            return out.toByteArray();
        });
    }

    /** Token accounting; idempotent by callId (the backend deduplicates retries and replays). */
    public ReportLlmUsageResponse reportLlmUsage(String callId, String agentId, String runId,
                                                 String providerId, String model,
                                                 int inputTokens, int outputTokens,
                                                 int cacheReadTokens, int cacheWriteTokens) {
        return call("ReportLlmUsage", () -> ctx().reportLlmUsage(ReportLlmUsageRequest.newBuilder()
                .setCallId(callId)
                .setAgentId(agentId)
                .setRunId(runId == null ? "" : runId)
                .setProviderId(providerId)
                .setModel(model == null ? "" : model)
                .setInputTokens(inputTokens)
                .setOutputTokens(outputTokens)
                .setCacheReadTokens(cacheReadTokens)
                .setCacheWriteTokens(cacheWriteTokens)
                .build()));
    }

    // ---- MessageLog --------------------------------------------------------------

    /**
     * Records a dialogue event; idempotent by (run_id, seq) — persistence and delivery happen on the
     * backend. {@code toolTurn} (nullable) is the structural record of a tool turn under a
     * PROGRESS line.
     */
    public SaveMessageResponse saveMessage(String agentId, String runId, int seq,
                                           MessageKind kind, ProgressType progressType, String text,
                                           ToolTurn toolTurn) {
        return call("SaveMessage", () -> {
            SaveMessageRequest.Builder request = SaveMessageRequest.newBuilder()
                    .setAgentId(agentId)
                    .setRunId(runId)
                    .setSeq(seq)
                    .setKind(kind)
                    .setProgressType(progressType)
                    .setText(text == null ? "" : text);
            if (toolTurn != null) {
                request.setToolTurn(toolTurn);
            }
            return messageLog.withDeadlineAfter(timeoutMs(), TimeUnit.MILLISECONDS)
                    .saveMessage(request.build());
        });
    }

    /**
     * The canonical turn of a run ({@code agent_run_turns}); idempotent by (run_id, turn_index). Not
     * a durable step at the caller — a turn is a projection of already-durable data, and a replay is
     * deduplicated by the backend. {@code finishReason}/{@code model}/{@code callId} are nullable.
     */
    public SaveTurnResponse saveTurn(String agentId, String runId, int turnIndex, TurnRole role,
                                     String text, String thinkingText,
                                     List<ToolCallRec> toolCalls,
                                     List<ToolResultRec> toolResults, String finishReason,
                                     String model, String callId) {
        return call("SaveTurn", () -> {
            SaveTurnRequest.Builder request = SaveTurnRequest.newBuilder()
                    .setAgentId(agentId)
                    .setRunId(runId)
                    .setTurnIndex(turnIndex)
                    .setRole(role)
                    .setText(text == null ? "" : text)
                    .setThinkingText(thinkingText == null ? "" : thinkingText)
                    .addAllToolCalls(toolCalls)
                    .addAllToolResults(toolResults)
                    .setFinishReason(finishReason == null ? "" : finishReason)
                    .setModel(model == null ? "" : model)
                    .setCallId(callId == null ? "" : callId);
            return messageLog.withDeadlineAfter(timeoutMs(), TimeUnit.MILLISECONDS)
                    .saveTurn(request.build());
        });
    }

    /**
     * Snapshot of the run's starting prompt ({@code agent_runs.prompt}): {@code promptJson} is the
     * JSON array of messages as it went into the first LLM call. Sent once before the loop,
     * first-write-wins on the backend.
     */
    public SavePromptResponse savePrompt(String agentId, String runId, String promptJson) {
        return call("SavePrompt", () -> messageLog.withDeadlineAfter(timeoutMs(), TimeUnit.MILLISECONDS)
                .savePrompt(SavePromptRequest.newBuilder()
                        .setAgentId(agentId)
                        .setRunId(runId)
                        .setPromptJson(promptJson == null ? "" : promptJson)
                        .build()));
    }

    // ---- ToolGateway -----------------------------------------------------------------

    public ExecuteToolAsyncAck executeToolAsync(
            String toolCallId, String connectorCode, String connectionId, String toolName,
            byte[] input, String agentId, String runId) {
        return call("ExecuteToolAsync", () -> tools.withDeadlineAfter(timeoutMs(), TimeUnit.MILLISECONDS)
                .executeToolAsync(ExecuteToolRequest.newBuilder()
                        .setToolCallId(toolCallId)
                        .setConnectorCode(connectorCode)
                        .setConnectionId(connectionId)
                        .setToolName(toolName)
                        .setInput(ByteString.copyFrom(input))
                        .setAgentId(agentId)
                        .setRunId(runId)
                        .build()));
    }

    /**
     * Single poll of the tool result; deadline applied so a hung backend does not block forever.
     * {@code runId} is the run's sign of life for the backend (it extends {@code last_activity_at}).
     */
    public GetToolResultResponse getToolResult(String agentId, String toolCallId, String runId) {
        return call("GetToolResult", () -> tools.withDeadlineAfter(timeoutMs(), TimeUnit.MILLISECONDS)
                .getToolResult(GetToolResultRequest.newBuilder()
                        .setAgentId(agentId).setToolCallId(toolCallId).setRunId(runId).build()));
    }

    /**
     * Stop waiting for a slow call and hand the result's ownership to the backend (trigger
     * delivery). The response settles the race: DETACHED, or the plain result of a call that
     * finished first.
     */
    public DetachToolResponse detachTool(String agentId, String toolCallId, String runId) {
        return call("DetachTool", () -> tools.withDeadlineAfter(timeoutMs(), TimeUnit.MILLISECONDS)
                .detachTool(DetachToolRequest.newBuilder()
                        .setAgentId(agentId).setToolCallId(toolCallId).setRunId(runId).build()));
    }

    // ---- WorkerControl ---------------------------------------------------------------

    public SendMessageResponse sendMessage(WorkerMessageType type, String content) {
        return call("SendMessage", () -> workerControl.withDeadlineAfter(timeoutMs(), TimeUnit.MILLISECONDS)
                .sendMessage(SendMessageRequest.newBuilder().setType(type).setContent(content).build()));
    }

}
