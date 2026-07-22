package ru.agimate.controlapi.grpc.service;

import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.controlapi.database.enums.ChannelSessionMessageKind;
import ru.agimate.controlapi.grpc.mapper.MessageKindMapper;
import ru.agimate.controlapi.service.channel.MessageLogService;
import ru.agimate.controlapi.service.dto.ToolTurnRecord;
import ru.agimate.agentworker.MessageLogGrpc;
import ru.agimate.agentworker.ProgressType;
import ru.agimate.agentworker.SaveMessageRequest;
import ru.agimate.agentworker.SaveMessageResponse;
import ru.agimate.agentworker.ToolTurn;

import java.util.UUID;

import static ru.agimate.controlapi.grpc.support.GrpcSupport.handleError;
import static ru.agimate.controlapi.grpc.support.GrpcSupport.parseUuid;

/**
 * SaveMessage (протокол v2): тонкий фасад над {@link MessageLogService} — запись события диалога
 * + доставка как её проекция. Идемпотентен по {@code (run_id, seq)}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MessageLogGrpcService extends MessageLogGrpc.MessageLogImplBase {

    private final MessageLogService messageLogService;

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
