package ru.agimate.controlapi.grpc.service;

import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.controlapi.database.enums.AgentTurnRole;
import ru.agimate.controlapi.database.enums.ChannelSessionMessageKind;
import ru.agimate.controlapi.grpc.mapper.MessageKindMapper;
import ru.agimate.controlapi.service.AgentRunPromptService;
import ru.agimate.controlapi.service.AgentRunTurnService;
import ru.agimate.controlapi.service.channel.MessageLogService;
import ru.agimate.controlapi.service.dto.ToolTurnRecord;
import ru.agimate.agentworker.MessageLogGrpc;
import ru.agimate.agentworker.ProgressType;
import ru.agimate.agentworker.SaveMessageRequest;
import ru.agimate.agentworker.SaveMessageResponse;
import ru.agimate.agentworker.SavePromptRequest;
import ru.agimate.agentworker.SavePromptResponse;
import ru.agimate.agentworker.SaveTurnRequest;
import ru.agimate.agentworker.SaveTurnResponse;
import ru.agimate.agentworker.ToolTurn;
import ru.agimate.agentworker.TurnRole;

import java.util.UUID;

import static ru.agimate.controlapi.grpc.support.GrpcSupport.handleError;
import static ru.agimate.controlapi.grpc.support.GrpcSupport.parseUuid;

/**
 * SaveMessage (protocol v2): a thin facade over {@link MessageLogService} — recording a dialogue event
 * plus delivery as its projection. Idempotent by {@code (run_id, seq)}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MessageLogGrpcService extends MessageLogGrpc.MessageLogImplBase {

    private final MessageLogService messageLogService;
    private final AgentRunTurnService agentRunTurnService;
    private final AgentRunPromptService agentRunPromptService;

    @Override
    public void saveMessage(SaveMessageRequest request, StreamObserver<SaveMessageResponse> responseObserver) {
        try {
            UUID agentId = parseUuid(request.getAgentId(), "agent_id");
            UUID runId = parseUuid(request.getRunId(), "run_id");
            if (request.getSeq() < 0) {
                throw new BadRequestStatusException("seq must be >= 0");
            }
            ChannelSessionMessageKind kind = MessageKindMapper.toDomain(request.getKind());
            String progressType = request.getProgressType() == ProgressType.PROGRESS_TYPE_UNSPECIFIED
                    ? null
                    : request.getProgressType().name().replace("PROGRESS_TYPE_", "");

            MessageLogService.SaveResult result = messageLogService.save(
                    agentId, runId, request.getSeq(), kind, progressType, request.getText(),
                    request.hasToolTurn() ? toDomain(request.getToolTurn()) : null);

            responseObserver.onNext(SaveMessageResponse.newBuilder()
                    .setDuplicate(result.duplicate())
                    .build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            handleError(e, responseObserver, "SaveMessage agent=" + request.getAgentId()
                    + " run=" + request.getRunId());
        }
    }

    @Override
    public void saveTurn(SaveTurnRequest request, StreamObserver<SaveTurnResponse> responseObserver) {
        try {
            UUID agentId = parseUuid(request.getAgentId(), "agent_id");
            UUID runId = parseUuid(request.getRunId(), "run_id");
            if (request.getTurnIndex() < 0) {
                throw new BadRequestStatusException("turn_index must be >= 0");
            }

            AgentRunTurnService.SaveResult result = agentRunTurnService.save(
                    agentId, runId, request.getTurnIndex(), toDomain(request.getRole()),
                    request.getText(), request.getThinking(),
                    request.getToolCallsList().stream()
                            .map(c -> new ToolTurnRecord.Call(c.getId(), c.getName(), c.getArgumentsJson()))
                            .toList(),
                    request.getToolResultsList().stream()
                            .map(r -> new ToolTurnRecord.Result(r.getId(), r.getName(), r.getOutputJson(),
                                    r.getFailed()))
                            .toList(),
                    request.getFinishReason(), request.getModel(), request.getCallId());

            responseObserver.onNext(SaveTurnResponse.newBuilder()
                    .setDuplicate(result.duplicate())
                    .build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            handleError(e, responseObserver, "SaveTurn agent=" + request.getAgentId()
                    + " run=" + request.getRunId());
        }
    }

    @Override
    public void savePrompt(SavePromptRequest request, StreamObserver<SavePromptResponse> responseObserver) {
        try {
            UUID agentId = parseUuid(request.getAgentId(), "agent_id");
            UUID runId = parseUuid(request.getRunId(), "run_id");

            AgentRunPromptService.SaveResult result =
                    agentRunPromptService.save(agentId, runId, request.getPromptJson());

            responseObserver.onNext(SavePromptResponse.newBuilder()
                    .setStored(result.stored())
                    .build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            handleError(e, responseObserver, "SavePrompt agent=" + request.getAgentId()
                    + " run=" + request.getRunId());
        }
    }

    private static AgentTurnRole toDomain(TurnRole role) {
        return switch (role) {
            case TURN_ROLE_SYSTEM -> AgentTurnRole.SYSTEM;
            case TURN_ROLE_USER -> AgentTurnRole.USER;
            case TURN_ROLE_ASSISTANT -> AgentTurnRole.ASSISTANT;
            case TURN_ROLE_TOOL -> AgentTurnRole.TOOL;
            case TURN_ROLE_UNSPECIFIED, UNRECOGNIZED ->
                    throw new BadRequestStatusException("role is required");
        };
    }

    private static ToolTurnRecord toDomain(ToolTurn turn) {
        return new ToolTurnRecord(
                turn.getText(),
                turn.getCallsList().stream()
                        .map(c -> new ToolTurnRecord.Call(c.getId(), c.getName(), c.getArgumentsJson()))
                        .toList(),
                turn.getResultsList().stream()
                        .map(r -> new ToolTurnRecord.Result(r.getId(), r.getName(), r.getOutputJson(),
                                r.getFailed()))
                        .toList());
    }
}
