package ru.agimate.agentworker.workers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentDispatcherTest {

    @Test
    @DisplayName("effectiveToolCallId keeps a provider id and generates a UUID for null/blank")
    void toolCallIdFallback() {
        assertEquals("call_123", AgentDispatcher.effectiveToolCallId("call_123"));
        String generated = AgentDispatcher.effectiveToolCallId(null);
        assertFalse(generated.isBlank());
        assertFalse(AgentDispatcher.effectiveToolCallId("  ").isBlank());
    }

    @Test
    @DisplayName("errorJson stays valid JSON for control characters and quotes")
    void errorJsonEscaping() throws Exception {
        String raw = "line1\r\nline2\twith \"quotes\" and \\backslash";
        JsonNode parsed = new ObjectMapper().readTree(AgentDispatcher.errorJson(raw));
        assertEquals(raw, parsed.get("error").asText());
        assertTrue(new ObjectMapper().readTree(AgentDispatcher.errorJson(null)).has("error"));
    }
}
