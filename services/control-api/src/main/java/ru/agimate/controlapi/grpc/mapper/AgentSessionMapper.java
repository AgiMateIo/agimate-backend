package ru.agimate.controlapi.grpc.mapper;

import com.google.protobuf.ByteString;
import com.google.protobuf.Int32Value;
import com.google.protobuf.StringValue;
import io.grpc.Status;
import lombok.experimental.UtilityClass;
import ru.agimate.agentworker.HistoryMessage;
import ru.agimate.agentworker.MessageKind;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.controlapi.database.entities.ChannelSessionMessage;
import ru.agimate.controlapi.database.enums.ChannelSessionMessageKind;

import static ru.agimate.controlapi.grpc.support.GrpcSupport.toProtoTimestamp;

/** Маппинг истории сессии: entity {@link ChannelSessionMessage} ↔ proto {@link HistoryMessage}/{@link MessageKind}. */
@UtilityClass
public class AgentSessionMapper {

    public static HistoryMessage toHistoryMessage(ChannelSessionMessage m) {
        String json = JsonUtils.writeValueAsStringSafe(m.getMessageJson());
        HistoryMessage.Builder b = HistoryMessage.newBuilder()
                .setTurnIdx(m.getTurnIdx())
                .setKind(toProtoKind(m.getKind()))
                .setMessageJson(ByteString.copyFromUtf8(json != null ? json : "{}"))
                .setCreatedAt(toProtoTimestamp(m.getCreatedAt()));
        if (m.getInputTokens() != null) {
            b.setInputTokens(Int32Value.of(m.getInputTokens()));
        }
        if (m.getOutputTokens() != null) {
            b.setOutputTokens(Int32Value.of(m.getOutputTokens()));
        }
        if (m.getModelName() != null) {
            b.setModelName(StringValue.of(m.getModelName()));
        }
        return b.build();
    }

    public static ChannelSessionMessageKind toDomainKind(MessageKind kind) {
        return switch (kind) {
            case REQUEST -> ChannelSessionMessageKind.REQUEST;
            case RESPONSE -> ChannelSessionMessageKind.RESPONSE;
            default -> throw Status.INVALID_ARGUMENT
                    .withDescription("Unknown message kind: " + kind).asRuntimeException();
        };
    }

    public static MessageKind toProtoKind(ChannelSessionMessageKind kind) {
        return switch (kind) {
            case REQUEST -> MessageKind.REQUEST;
            case RESPONSE -> MessageKind.RESPONSE;
        };
    }
}
