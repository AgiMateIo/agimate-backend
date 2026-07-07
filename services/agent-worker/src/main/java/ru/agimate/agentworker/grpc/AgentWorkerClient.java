package ru.agimate.agentworker.grpc;

import com.google.protobuf.ByteString;
import com.google.protobuf.StringValue;
import io.grpc.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.agimate.agentworker.AgentContextGrpc;
import ru.agimate.agentworker.AgentMemory;
import ru.agimate.agentworker.AgentSessionMessagesGrpc;
import ru.agimate.agentworker.AgentRunRegistryGrpc;
import ru.agimate.agentworker.AgentSpec;
import ru.agimate.agentworker.AppendMessage;
import ru.agimate.agentworker.AppendRequest;
import ru.agimate.agentworker.AppendResponse;
import ru.agimate.agentworker.ChannelGatewayGrpc;
import ru.agimate.agentworker.ExecuteToolAsyncAck;
import ru.agimate.agentworker.ExecuteToolRequest;
import ru.agimate.agentworker.GetActiveRunRequest;
import ru.agimate.agentworker.GetActiveRunResponse;
import ru.agimate.agentworker.GetAgentSpecRequest;
import ru.agimate.agentworker.GetConnectionToolsRequest;
import ru.agimate.agentworker.GetConnectionToolsResponse;
import ru.agimate.agentworker.GetConnectionsRequest;
import ru.agimate.agentworker.GetConnectionsResponse;
import ru.agimate.agentworker.GetHistoryRequest;
import ru.agimate.agentworker.GetHistoryResponse;
import ru.agimate.agentworker.GetLlmCredentialsRequest;
import ru.agimate.agentworker.GetMemoryNotesRequest;
import ru.agimate.agentworker.GetMemoryNotesResponse;
import ru.agimate.agentworker.GetMemoryRequest;
import ru.agimate.agentworker.GetSkillRequest;
import ru.agimate.agentworker.GetSkillsRequest;
import ru.agimate.agentworker.GetSkillsResponse;
import ru.agimate.agentworker.GetTeamContextRequest;
import ru.agimate.agentworker.GetToolResultRequest;
import ru.agimate.agentworker.GetToolResultResponse;
import ru.agimate.agentworker.LlmCredentials;
import ru.agimate.agentworker.MessageKind;
import ru.agimate.agentworker.OutboundMessage;
import ru.agimate.agentworker.RegisterRunRequest;
import ru.agimate.agentworker.RegisterRunResponse;
import ru.agimate.agentworker.ReleaseRunRequest;
import ru.agimate.agentworker.ReleaseRunResponse;
import ru.agimate.agentworker.SendChannelMessageRequest;
import ru.agimate.agentworker.SendChannelMessageResponse;
import ru.agimate.agentworker.SendMessageRequest;
import ru.agimate.agentworker.SendMessageResponse;
import ru.agimate.agentworker.SkillSpec;
import ru.agimate.agentworker.TeamContext;
import ru.agimate.agentworker.ToolGatewayGrpc;
import ru.agimate.agentworker.WorkerControlGrpc;
import ru.agimate.agentworker.WorkerMessageType;
import ru.agimate.agentworker.config.AgentProperties;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Blocking gRPC facade over control-api's worker services. Exposes only the RPCs the
 * worker actually uses (unlike the Python "full SDK mirror"), returning the generated
 * protobuf messages directly. The per-request {@code workflow_id} comes from config;
 * a per-call deadline is applied to every unary RPC.
 *
 * <p>{@code getToolResult} is polled by the tool worker and intentionally carries no
 * deadline of its own — its polling budget is enforced by the caller.
 */
@Component
@Slf4j
public class AgentWorkerClient {

    private final AgentProperties props;
    private final AgentContextGrpc.AgentContextBlockingStub agentContext;
    private final AgentSessionMessagesGrpc.AgentSessionMessagesBlockingStub sessions;
    private final ToolGatewayGrpc.ToolGatewayBlockingStub tools;
    private final ChannelGatewayGrpc.ChannelGatewayBlockingStub channels;
    private final WorkerControlGrpc.WorkerControlBlockingStub workerControl;
    private final AgentRunRegistryGrpc.AgentRunRegistryBlockingStub registry;

    public AgentWorkerClient(Channel controlApiAuthedChannel, AgentProperties props) {
        this.props = props;
        this.agentContext = AgentContextGrpc.newBlockingStub(controlApiAuthedChannel);
        this.sessions = AgentSessionMessagesGrpc.newBlockingStub(controlApiAuthedChannel);
        this.tools = ToolGatewayGrpc.newBlockingStub(controlApiAuthedChannel);
        this.channels = ChannelGatewayGrpc.newBlockingStub(controlApiAuthedChannel);
        this.workerControl = WorkerControlGrpc.newBlockingStub(controlApiAuthedChannel);
        this.registry = AgentRunRegistryGrpc.newBlockingStub(controlApiAuthedChannel);
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

    // ---- AgentContext ----------------------------------------------------------------

    public AgentSpec getAgentSpec(String agentId) {
        return ctx().getAgentSpec(GetAgentSpecRequest.newBuilder()
                .setWorkflowId(workflowId()).setAgentId(agentId).build());
    }

    public GetSkillsResponse getSkills(String agentId) {
        return ctx().getSkills(GetSkillsRequest.newBuilder()
                .setWorkflowId(workflowId()).setAgentId(agentId).build());
    }

    public SkillSpec getSkill(String skillId) {
        return ctx().getSkill(GetSkillRequest.newBuilder()
                .setWorkflowId(workflowId()).setSkillId(skillId).build());
    }

    public TeamContext getTeamContext(String teamId) {
        return ctx().getTeamContext(GetTeamContextRequest.newBuilder()
                .setWorkflowId(workflowId()).setTeamId(teamId).build());
    }

    public LlmCredentials getLlmCredentials(String agentId) {
        return ctx().getLlmCredentials(GetLlmCredentialsRequest.newBuilder()
                .setWorkflowId(workflowId()).setAgentId(agentId).build());
    }

    public GetConnectionsResponse getConnections(String agentId) {
        return ctx().getConnections(GetConnectionsRequest.newBuilder().setAgentId(agentId).build());
    }

    public GetConnectionToolsResponse getConnectionTools(String connectionId) {
        return ctx().getConnectionTools(GetConnectionToolsRequest.newBuilder()
                .setConnectionId(connectionId).build());
    }

    public AgentMemory getMemory(String agentId) {
        return ctx().getMemory(GetMemoryRequest.newBuilder()
                .setWorkflowId(workflowId()).setAgentId(agentId).build());
    }

    public GetMemoryNotesResponse getMemoryNotes(String agentId) {
        return ctx().getMemoryNotes(GetMemoryNotesRequest.newBuilder()
                .setWorkflowId(workflowId()).setAgentId(agentId).build());
    }

    // ---- AgentSessionMessages --------------------------------------------------------

    /** One message to append; {@code text}/{@code triggerInputJson} may be null. */
    public record AppendItem(MessageKind kind, byte[] messageJson, String text, byte[] triggerInputJson) {}

    public List<Integer> appendSessionMessages(
            String agentPubId, String sessionPubId, String runId, int startingTurnIdx, List<AppendItem> items) {
        AppendRequest.Builder req = AppendRequest.newBuilder()
                .setAgentPubId(agentPubId)
                .setSessionPubId(sessionPubId)
                .setRunId(runId)
                .setStartingTurnIdx(startingTurnIdx);
        for (AppendItem it : items) {
            AppendMessage.Builder m = AppendMessage.newBuilder()
                    .setKind(it.kind())
                    .setMessageJson(ByteString.copyFrom(it.messageJson()));
            if (it.text() != null) {
                m.setText(StringValue.of(it.text()));
            }
            if (it.triggerInputJson() != null) {
                m.setTriggerInputJson(ByteString.copyFrom(it.triggerInputJson()));
            }
            req.addMessages(m);
        }
        AppendResponse resp = sessions.withDeadlineAfter(timeoutMs(), TimeUnit.MILLISECONDS).append(req.build());
        return resp.getAssignedTurnIndicesList();
    }

    public GetHistoryResponse getHistory(String agentPubId, String sessionPubId, int lastNMessages) {
        return sessions.withDeadlineAfter(timeoutMs(), TimeUnit.MILLISECONDS)
                .getHistory(GetHistoryRequest.newBuilder()
                        .setAgentPubId(agentPubId)
                        .setSessionPubId(sessionPubId)
                        .setLastNMessages(lastNMessages)
                        .build());
    }

    // ---- ToolGateway -----------------------------------------------------------------

    public ExecuteToolAsyncAck executeToolAsync(
            String toolCallId, String connectorCode, String identity, String toolName,
            byte[] input, String agentId, String agentSessionId) {
        return tools.withDeadlineAfter(timeoutMs(), TimeUnit.MILLISECONDS)
                .executeToolAsync(ExecuteToolRequest.newBuilder()
                        .setToolCallId(toolCallId)
                        .setConnectorCode(connectorCode)
                        .setIdentity(identity)
                        .setToolName(toolName)
                        .setInput(ByteString.copyFrom(input))
                        .setAgentId(agentId)
                        .setWorkflowId(workflowId())
                        .setAgentSessionId(agentSessionId)
                        .build());
    }

    /** Single poll of the tool result; deadline applied so a hung backend does not block forever. */
    public GetToolResultResponse getToolResult(String agentId, String toolCallId) {
        return tools.withDeadlineAfter(timeoutMs(), TimeUnit.MILLISECONDS)
                .getToolResult(GetToolResultRequest.newBuilder()
                        .setAgentId(agentId).setToolCallId(toolCallId).build());
    }

    // ---- ChannelGateway --------------------------------------------------------------

    public SendChannelMessageResponse sendChannelMessage(
            String agentId, String channelId, String sessionId, String messageId, String text) {
        return channels.withDeadlineAfter(timeoutMs(), TimeUnit.MILLISECONDS)
                .sendChannelMessage(SendChannelMessageRequest.newBuilder()
                        .setAgentId(agentId)
                        .setChannelId(channelId)
                        .setSessionId(sessionId)
                        .setMessageId(messageId)
                        .setMessage(OutboundMessage.newBuilder().setText(text).build())
                        .build());
    }

    // ---- WorkerControl ---------------------------------------------------------------

    public SendMessageResponse sendMessage(WorkerMessageType type, String content) {
        return workerControl.withDeadlineAfter(timeoutMs(), TimeUnit.MILLISECONDS)
                .sendMessage(SendMessageRequest.newBuilder().setType(type).setContent(content).build());
    }

    // ---- AgentRunRegistry ------------------------------------------------------------

    /** Atomic claim of a session's active-run slot; the gRPC status is ABORTED when already held. */
    public RegisterRunResponse registerRun(String agentPubId, String sessionPubId, String runId, int ttlSeconds) {
        return registry.withDeadlineAfter(timeoutMs(), TimeUnit.MILLISECONDS)
                .registerRun(RegisterRunRequest.newBuilder()
                        .setAgentPubId(agentPubId)
                        .setSessionPubId(sessionPubId)
                        .setRunId(runId)
                        .setTtlSeconds(ttlSeconds)
                        .build());
    }

    public GetActiveRunResponse getActiveRun(String sessionPubId) {
        return registry.withDeadlineAfter(timeoutMs(), TimeUnit.MILLISECONDS)
                .getActiveRun(GetActiveRunRequest.newBuilder().setSessionPubId(sessionPubId).build());
    }

    public ReleaseRunResponse releaseRun(String sessionPubId, String runId) {
        return registry.withDeadlineAfter(timeoutMs(), TimeUnit.MILLISECONDS)
                .releaseRun(ReleaseRunRequest.newBuilder()
                        .setSessionPubId(sessionPubId).setRunId(runId).build());
    }
}
