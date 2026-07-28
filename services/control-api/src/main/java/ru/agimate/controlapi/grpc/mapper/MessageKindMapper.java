package ru.agimate.controlapi.grpc.mapper;

import lombok.experimental.UtilityClass;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.controlapi.database.enums.ChannelSessionMessageKind;
import ru.agimate.agentworker.MessageKind;

/**
 * Bidirectional mapping of a message's kind: proto {@link MessageKind} ↔ the domain
 * {@link ChannelSessionMessageKind}. The single source of the correspondence for both gRPC boundaries
 * (SaveMessage — incoming, GetRunContext.history — outgoing).
 */
@UtilityClass
public class MessageKindMapper {

    public static MessageKind toProto(ChannelSessionMessageKind kind) {
        return switch (kind) {
            case INBOUND -> MessageKind.MESSAGE_KIND_INBOUND;
            case PROGRESS -> MessageKind.MESSAGE_KIND_PROGRESS;
            case ANSWER -> MessageKind.MESSAGE_KIND_ANSWER;
            case ERROR -> MessageKind.MESSAGE_KIND_ERROR;
        };
    }

    public static ChannelSessionMessageKind toDomain(MessageKind kind) {
        return switch (kind) {
            case MESSAGE_KIND_INBOUND -> ChannelSessionMessageKind.INBOUND;
            case MESSAGE_KIND_PROGRESS -> ChannelSessionMessageKind.PROGRESS;
            case MESSAGE_KIND_ANSWER -> ChannelSessionMessageKind.ANSWER;
            case MESSAGE_KIND_ERROR -> ChannelSessionMessageKind.ERROR;
            default -> throw new BadRequestStatusException("Unknown message kind: " + kind);
        };
    }
}
