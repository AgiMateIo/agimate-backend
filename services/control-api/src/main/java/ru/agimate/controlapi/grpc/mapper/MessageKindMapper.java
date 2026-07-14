package ru.agimate.controlapi.grpc.mapper;

import lombok.experimental.UtilityClass;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.controlapi.database.enums.ChannelSessionMessageKind;
import ru.agimate.agentworker.MessageKind;

/**
 * Двунаправленный маппинг рода сообщения: proto {@link MessageKind} ↔ доменный
 * {@link ChannelSessionMessageKind}. Единый источник соответствия для обеих gRPC-границ
 * (SaveMessage — входящий, GetRunContext.history — исходящий).
 */
@UtilityClass
public class MessageKindMapper {

    public static MessageKind toProto(ChannelSessionMessageKind kind) {
        return switch (kind) {
            // Дореформенные kinds сервис уже маппит на v2; ветки здесь — на случай пропуска.
            case INBOUND, REQUEST -> MessageKind.MESSAGE_KIND_INBOUND;
            case PROGRESS -> MessageKind.MESSAGE_KIND_PROGRESS;
            case ANSWER, RESPONSE -> MessageKind.MESSAGE_KIND_ANSWER;
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
