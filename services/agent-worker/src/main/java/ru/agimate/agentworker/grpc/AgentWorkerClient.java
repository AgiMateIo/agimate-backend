package ru.agimate.agentworker.grpc;

import com.google.protobuf.ByteString;
import io.grpc.Channel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.agimate.agentworker.AgentContextGrpc;
import ru.agimate.agentworker.ExecuteToolAsyncAck;
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
import ru.agimate.agentworker.SendMessageRequest;
import ru.agimate.agentworker.SendMessageResponse;
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
 * Blocking gRPC facade over control-api's worker services. Exposes only the RPCs the
 * worker actually uses (unlike the Python "full SDK mirror"), returning the generated
 * protobuf messages directly. The per-request {@code workflow_id} comes from config;
 * a per-call deadline is applied to every unary RPC.
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

    private String workflowId() {
        return props.getAgent().getWorkflowId();
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
                // Ожидаемые исходы, не инфра-сбой: ABORTED (занятый active-run claim) и
                // RESOURCE_EXHAUSTED (исчерпана квота LLM) — тихо и без стектрейса, никогда не ретраятся.
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

    /** Весь контекст рана одним вызовом: упорядоченные блоки промпта + отскоупленные тулы. */
    public RunContext getRunContext(String agentId, String runId) {
        return call("GetRunContext", () -> ctx().getRunContext(GetRunContextRequest.newBuilder()
                .setAgentId(agentId).setRunId(runId).build()));
    }

    public LlmCredentials getLlmCredentials(String agentId) {
        return call("GetLlmCredentials", () -> ctx().getLlmCredentials(GetLlmCredentialsRequest.newBuilder()
                .setWorkflowId(workflowId()).setAgentId(agentId).build()));
    }

    /** Дедлайн стрима содержимого файла — больше unary-таймаута: файл может быть крупным. */
    private static final long FILE_STREAM_DEADLINE_MS = 60_000;
    /** Потолок собираемого в память файла — защита от бесконечного/раздутого стрима. */
    private static final int FILE_MAX_BYTES = 32 * 1024 * 1024;

    /**
     * Содержимое inbound-вложения (server-streaming чанки) в {@code byte[]}. Тянется inline при
     * LLM-вызове — как {@link #getLlmCredentials}, вне DBOS-чекпоинта. Слишком большой стрим
     * обрывается {@code OUT_OF_RANGE}.
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

    /** Учёт расхода токенов; идемпотентен по callId (бэк дедуплицирует повторы/реплеи). */
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
     * Запись события диалога; идемпотентна по (run_id, seq) — персист и доставка на бэке.
     * {@code toolTurn} (nullable) — структурная запись tool-хода при PROGRESS/TOOL_CALL (v2.1).
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
                        .setWorkflowId(workflowId())
                        .setRunId(runId)
                        .build()));
    }

    /**
     * Single poll of the tool result; deadline applied so a hung backend does not block forever.
     * {@code runId} — признак жизни рана для бэка (продлевает {@code last_activity_at}).
     */
    public GetToolResultResponse getToolResult(String agentId, String toolCallId, String runId) {
        return call("GetToolResult", () -> tools.withDeadlineAfter(timeoutMs(), TimeUnit.MILLISECONDS)
                .getToolResult(GetToolResultRequest.newBuilder()
                        .setAgentId(agentId).setToolCallId(toolCallId).setRunId(runId).build()));
    }

    // ---- WorkerControl ---------------------------------------------------------------

    public SendMessageResponse sendMessage(WorkerMessageType type, String content) {
        return call("SendMessage", () -> workerControl.withDeadlineAfter(timeoutMs(), TimeUnit.MILLISECONDS)
                .sendMessage(SendMessageRequest.newBuilder().setType(type).setContent(content).build()));
    }

}
