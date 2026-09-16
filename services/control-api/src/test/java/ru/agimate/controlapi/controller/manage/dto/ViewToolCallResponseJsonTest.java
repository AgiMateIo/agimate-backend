package ru.agimate.controlapi.controller.manage.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Handed to the view as-is, so the MCP field names are the contract — checked with the HTTP boundary's Jackson 3. */
@DisplayName("ViewToolCallResponse — CallToolResult на проводе")
class ViewToolCallResponseJsonTest {

    @Test
    @DisplayName("isError называется isError, а отсутствующий structuredContent не выводится")
    void wireShape() {
        String json = JsonMapper.builder().build().writeValueAsString(
                new ViewToolCallResponse(List.of(Map.of("text", "ok")), null, false));

        assertEquals("{\"content\":[{\"text\":\"ok\"}],\"isError\":false}", json);
    }
}
