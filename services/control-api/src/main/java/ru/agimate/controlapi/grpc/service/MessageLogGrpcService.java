package ru.agimate.controlapi.grpc.service;

import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.controlapi.database.enums.ChannelSessionMessageKind;
import ru.agimate.controlapi.service.channel.MessageLogService;
import ru.agimate.agentworker.MessageKind;
import ru.agimate.agentworker.MessageLogGrpc;
import ru.agimate.agentworker.ProgressType;
import ru.agimate.agentworker.SaveMessageRequest;
import ru.agimate.agentworker.SaveMessageResponse;

import java.util.UUID;

import static ru.agimate.controlapi.grpc.support.GrpcSupport.handleError;
import static ru.agimate.controlapi.grpc.support.GrpcSupport.parseUuid;

/**
 * SaveMessage (протокол v2): тонкий фасад над {@link MessageLogService} — запись события диалога
 * + доставка как её проекция. Идемпотентен по {@code (trigger_id, seq)}.
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
            UUID triggerId = parseUuid(request.getTriggerId(), "trigger_id");
            if (request.getSeq() < 0) {
                throw new BadRequestStatusException("seq must be >= 0");
            }
            ChannelSessionMessageKind kind = mapKind(request.getKind());
            String progressType = request.getProgressType() == ProgressType.PROGRESS_TYPE_UNSPECIFIED
                    ? null
                    : request.getProgressType().name().replace("PROGRESS_TYPE_", "");

            MessageLogService.SaveResult result = messageLogService.save(
                    agentId, triggerId, request.getSeq(), kind, progressType, request.getText());

            responseObserver.onNext(SaveMessageResponse.newBuilder()
                    .setDuplicate(result.duplicate())
                    .build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            handleError(e, responseObserver);
        }
    }

    private static ChannelSessionMessageKind mapKind(MessageKind kind) {
        return switch (kind) {
            case MESSAGE_KIND_INBOUND -> ChannelSessionMessageKind.INBOUND;
            case MESSAGE_KIND_PROGRESS -> ChannelSessionMessageKind.PROGRESS;
            case MESSAGE_KIND_ANSWER -> ChannelSessionMessageKind.ANSWER;
            case MESSAGE_KIND_ERROR -> ChannelSessionMessageKind.ERROR;
            default -> throw new BadRequestStatusException("Unknown message kind: " + kind);
        };
    }
}
