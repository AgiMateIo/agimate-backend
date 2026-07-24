package ru.agimate.controlapi.grpc.mapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.controlapi.database.enums.ChannelSessionMessageKind;
import ru.agimate.agentworker.MessageKind;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("MessageKindMapper")
class MessageKindMapperTest {

    @Test
    @DisplayName("toDomain: каждый proto-kind → доменный")
    void toDomain() {
        assertEquals(ChannelSessionMessageKind.INBOUND, MessageKindMapper.toDomain(MessageKind.MESSAGE_KIND_INBOUND));
        assertEquals(ChannelSessionMessageKind.PROGRESS, MessageKindMapper.toDomain(MessageKind.MESSAGE_KIND_PROGRESS));
        assertEquals(ChannelSessionMessageKind.ANSWER, MessageKindMapper.toDomain(MessageKind.MESSAGE_KIND_ANSWER));
        assertEquals(ChannelSessionMessageKind.ERROR, MessageKindMapper.toDomain(MessageKind.MESSAGE_KIND_ERROR));
    }

    @Test
    @DisplayName("toDomain: UNSPECIFIED → BadRequest")
    void toDomainRejectsUnknown() {
        assertThrows(BadRequestStatusException.class,
                () -> MessageKindMapper.toDomain(MessageKind.MESSAGE_KIND_UNSPECIFIED));
    }

    @Test
    @DisplayName("toProto: доменные kinds → proto")
    void toProto() {
        assertEquals(MessageKind.MESSAGE_KIND_INBOUND, MessageKindMapper.toProto(ChannelSessionMessageKind.INBOUND));
        assertEquals(MessageKind.MESSAGE_KIND_PROGRESS, MessageKindMapper.toProto(ChannelSessionMessageKind.PROGRESS));
        assertEquals(MessageKind.MESSAGE_KIND_ANSWER, MessageKindMapper.toProto(ChannelSessionMessageKind.ANSWER));
        assertEquals(MessageKind.MESSAGE_KIND_ERROR, MessageKindMapper.toProto(ChannelSessionMessageKind.ERROR));
    }
}
