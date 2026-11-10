package ru.agimate.controlapi.controller.manage.dto.session;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The row goes to the frontend as-is, so the field names — and which nulls stay keys — are the contract. */
@DisplayName("SessionMessageResponse — toolCalls на проводе")
class SessionMessageResponseJsonTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    @DisplayName("строка без вызовов несёт toolCalls: null ключом, а не пропускает поле")
    void nullToolCallsStaysAKey() {
        String json = mapper.writeValueAsString(new SessionMessageResponse(
                null, null, "USER", null, "привет", null, null, null, null));

        assertEquals("{\"id\":null,\"messageId\":null,\"direction\":\"USER\",\"stream\":null,"
                + "\"text\":\"привет\",\"parts\":null,\"toolCallIds\":null,\"toolCalls\":null,"
                + "\"createdAt\":null}", json);
    }

    @Test
    @DisplayName("viewUri: null выводится ключом — «вью нет» и «не резолвили» остаются различимы")
    void viewUriNullStaysAKey() {
        String json = mapper.writeValueAsString(new SessionMessageResponse(
                null, null, "AGENT", "progress", "🔧 get_memory", null, List.of("c1"),
                List.of(new ToolCallDescriptor("c1", "get_memory", "conn-1", null)),
                null));

        assertEquals("{\"id\":null,\"messageId\":null,\"direction\":\"AGENT\",\"stream\":\"progress\","
                + "\"text\":\"🔧 get_memory\",\"parts\":null,\"toolCallIds\":[\"c1\"],"
                + "\"toolCalls\":[{\"toolCallId\":\"c1\",\"name\":\"get_memory\","
                + "\"connectionId\":\"conn-1\",\"viewUri\":null}],\"createdAt\":null}", json);
    }
}
