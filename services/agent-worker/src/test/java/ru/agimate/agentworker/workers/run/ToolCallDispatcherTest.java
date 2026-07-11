package ru.agimate.agentworker.workers.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolCallDispatcherTest {

    private static ToolCallDispatcher dispatcher(String triggerId) {
        return new ToolCallDispatcher(null, null, null, "agent-1", triggerId, null);
    }

    @Test
    @DisplayName("effectiveToolCallId keeps a provider id as is")
    void toolCallIdKeepsProviderId() {
        assertEquals("call_123", dispatcher("run-1").effectiveToolCallId("call_123"));
    }

    @Test
    @DisplayName("fallback id детерминирован: тот же ран и порядок → те же id (crash-replay)")
    void toolCallIdFallbackDeterministic() {
        ToolCallDispatcher first = dispatcher("run-1");
        ToolCallDispatcher replay = dispatcher("run-1");
        assertEquals(first.effectiveToolCallId(null), replay.effectiveToolCallId(null));
        assertEquals(first.effectiveToolCallId("  "), replay.effectiveToolCallId("  "));
    }

    @Test
    @DisplayName("fallback id уникален внутри рана и между ранами")
    void toolCallIdFallbackUnique() {
        ToolCallDispatcher run1 = dispatcher("run-1");
        String a = run1.effectiveToolCallId(null);
        String b = run1.effectiveToolCallId(null);
        assertNotEquals(a, b);
        assertNotEquals(a, dispatcher("run-2").effectiveToolCallId(null));
        assertTrue(!a.isBlank());
    }

    @Test
    @DisplayName("errorJson stays valid JSON for control characters and quotes")
    void errorJsonEscaping() throws Exception {
        String raw = "line1\r\nline2\twith \"quotes\" and \\backslash";
        JsonNode parsed = new ObjectMapper().readTree(ToolCallDispatcher.errorJson(raw));
        assertEquals(raw, parsed.get("error").asText());
        assertTrue(new ObjectMapper().readTree(ToolCallDispatcher.errorJson(null)).has("error"));
    }
}
