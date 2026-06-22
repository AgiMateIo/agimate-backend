package ru.agimate.controlapi.grpc.mapper;

import lombok.experimental.UtilityClass;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.controlapi.service.channel.handler.dto.OutboundMessage;
import ru.agimate.controlapi.service.channel.handler.dto.Part;

import java.util.List;
import java.util.Map;

/** Маппинг proto-сообщений {@link ru.agimate.agentworker.ChannelGatewayProto} ↔ доменных DTO канала. */
@UtilityClass
public class ChannelGatewayMapper {

    public static OutboundMessage toOutboundMessage(ru.agimate.agentworker.OutboundMessage message) {
        List<Part> parts = message.getPartsList().stream()
                .map(ChannelGatewayMapper::toPart)
                .toList();
        return new OutboundMessage(message.getText(), parts);
    }

    public static Part toPart(ru.agimate.agentworker.Part part) {
        Map<String, Object> meta = part.getMetaJson().isEmpty()
                ? Map.of()
                : JsonUtils.readValue(part.getMetaJson(), JsonUtils.MAP_TYPE_REFERENCE);
        return new Part(part.getType(), part.getStorageRef(), part.getMime(), part.getSize(), meta);
    }
}
