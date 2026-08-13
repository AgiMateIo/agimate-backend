package ru.agimate.controlapi.controller.mcp.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.agimate.controlapi.database.entities.ToolCallLog;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The wire shape of results, serialized with Jackson 3 — the generation Spring Boot 4 puts on the
 * HTTP boundary. {@code resultType} lives on a default interface method rather than a record
 * component, and only a serialization test proves the {@code @JsonProperty} actually lands in JSON.
 */
@DisplayName("McpResult — resultType на проводе")
class McpResultJsonTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();

    @Test
    @DisplayName("ping: пустой результат — это ровно {\"resultType\":\"complete\"}")
    void emptyResultIsJustTheDiscriminator() {
        assertEquals("{\"resultType\":\"complete\"}", mapper.writeValueAsString(EmptyResult.INSTANCE));
    }

    @Test
    @DisplayName("таск против его же вида из tasks/get: task и complete на одном поле")
    void taskDiscriminators() {
        ToolCallLog row = ToolCallLog.builder().externalId("t1").build();
        row.setCreatedAt(LocalDateTime.of(2026, 8, 13, 12, 0));
        row.setUpdatedAt(LocalDateTime.of(2026, 8, 13, 12, 0));

        String created = mapper.writeValueAsString(TaskResult.created(row, 86_400_000L, 5000));
        String working = mapper.writeValueAsString(TaskResult.working(row, 86_400_000L, 5000));

        assertTrue(created.contains("\"resultType\":\"task\""), created);
        assertTrue(working.contains("\"resultType\":\"complete\""), working);
        assertTrue(created.contains("\"taskId\":\"t1\""), created);
        assertFalse(created.contains("\"result\""), "нет результата — нет и поля: " + created);
    }

    @Test
    @DisplayName("каждый результат ревизии несёт resultType: complete")
    void everyResultCarriesTheDiscriminator() {
        List<McpResult> results = List.of(
                new InitializeResult("2026-07-28", Map.of(), new InitializeResult.ServerInfo("agimate", "1")),
                new DiscoverResult(List.of("2026-07-28"), Map.of(), Map.of()),
                new ToolsListResult(List.of()),
                ToolCallResult.text("ok", null),
                ToolCallResult.error("boom"));

        for (McpResult result : results) {
            String json = mapper.writeValueAsString(result);
            assertTrue(json.contains("\"resultType\":\"complete\""),
                    result.getClass().getSimpleName() + " должен нести дискриминатор: " + json);
        }
    }
}
