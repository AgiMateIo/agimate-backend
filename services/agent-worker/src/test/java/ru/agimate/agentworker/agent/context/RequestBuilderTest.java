package ru.agimate.agentworker.agent.context;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import ru.agimate.agentworker.MemoryNote;
import ru.agimate.agentworker.agent.model.AgentChatMessage;
import ru.agimate.agentworker.dto.Trigger;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestBuilderTest {

    private static Trigger trigger(String connectorCode, String name) {
        return new Trigger(connectorCode, "ident", name, "id-1", Map.of("k", "v"), "2026-07-06T00:00:00Z");
    }

    @Nested
    @DisplayName("untrusted trigger wrapping")
    class Untrusted {
        @Test
        @DisplayName("wraps the event as data with delimiters and a count")
        void wrap() {
            String req = RequestBuilder.buildUntrustedTriggerRequest(trigger("telegram", "message"));
            assertTrue(req.contains("(триггеров): 1"));
            assertTrue(req.contains("<untrusted_event_data>"));
            assertTrue(req.contains("</untrusted_event_data>"));
            assertTrue(req.contains("telegram"));
        }
    }

    @Nested
    @DisplayName("renderMemoryNotes")
    class Notes {
        @Test
        @DisplayName("renders non-blank notes and returns null when empty")
        void notes() {
            String rendered = RequestBuilder.renderMemoryNotes(List.of(
                    MemoryNote.newBuilder().setContent("note one").build(),
                    MemoryNote.newBuilder().setContent("  ").build()));
            assertTrue(rendered.contains("<memory_notes>"));
            assertTrue(rendered.contains("- note one"));
            assertNull(RequestBuilder.renderMemoryNotes(List.of()));
        }
    }

    @Nested
    @DisplayName("withMemoryNotes")
    class WithNotes {
        @Test
        @DisplayName("appends the rendered notes to the request text as a user turn")
        void appends() {
            AgentChatMessage request = AgentChatMessage.user("hello");
            AgentChatMessage mixed = RequestBuilder.withMemoryNotes(request, "NOTES");
            assertEquals(AgentChatMessage.Role.USER, mixed.role());
            assertEquals("hello\n\nNOTES", mixed.text());
        }

        @Test
        @DisplayName("returns the request untouched when there are no notes")
        void untouched() {
            AgentChatMessage request = AgentChatMessage.user("hello");
            assertSame(request, RequestBuilder.withMemoryNotes(request, null));
        }

        @Test
        @DisplayName("treats a null request text as empty")
        void nullText() {
            AgentChatMessage request = AgentChatMessage.user(null);
            assertEquals("\n\nNOTES", RequestBuilder.withMemoryNotes(request, "NOTES").text());
        }
    }
}
