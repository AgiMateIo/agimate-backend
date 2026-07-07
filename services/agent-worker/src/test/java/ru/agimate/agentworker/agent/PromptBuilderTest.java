package ru.agimate.agentworker.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import ru.agimate.agentworker.AgentMemory;
import ru.agimate.agentworker.AgentSpec;
import ru.agimate.agentworker.MemoryNote;
import ru.agimate.agentworker.SkillRef;
import ru.agimate.agentworker.dto.Trigger;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptBuilderTest {

    private static SkillRef skill(String id, String name, String... codes) {
        return SkillRef.newBuilder().setSkillId(id).setName(name).addAllConnectorCodes(List.of(codes)).build();
    }

    private static Trigger trigger(String connectorCode, String name) {
        return new Trigger(connectorCode, "ident", name, "id-1", Map.of("k", "v"), "2026-07-06T00:00:00Z");
    }

    @Nested
    @DisplayName("build")
    class Build {
        @Test
        @DisplayName("includes agent block, system prompt, memory and skills")
        void full() {
            AgentSpec spec = AgentSpec.newBuilder()
                    .setAgentId("a-1").setName("Bot").setSystemPrompt("You are helpful.").build();
            AgentMemory memory = AgentMemory.newBuilder().setContent("remembered fact").setVersion(3).build();
            String prompt = PromptBuilder.build(spec, null,
                    List.of(skill("s1", "Board", "board")), List.of(), memory);

            assertTrue(prompt.contains("## Agent"));
            assertTrue(prompt.contains("- id: a-1"));
            assertTrue(prompt.contains("You are helpful."));
            assertTrue(prompt.contains("<memory version=3>"));
            assertTrue(prompt.contains("## Skills"));
            assertTrue(prompt.contains("connector_codes: board"));
        }
    }

    @Nested
    @DisplayName("skill selection")
    class SkillSelection {
        @Test
        @DisplayName("selects skills declaring a trigger's connector, deduped across a batch")
        void select() {
            List<SkillRef> skills = List.of(
                    skill("s1", "Board", "board"),
                    skill("s2", "Time", "time"),
                    skill("s3", "Boards2", "board"));
            List<SkillRef> matched = PromptBuilder.selectBatchSkills(skills,
                    List.of(trigger("board", "task.created"), trigger("board", "task.moved")));
            assertEquals(List.of("s1", "s3"), matched.stream().map(SkillRef::getSkillId).toList());
        }
    }

    @Nested
    @DisplayName("untrusted trigger wrapping")
    class Untrusted {
        @Test
        @DisplayName("wraps the event as data with delimiters and a count")
        void wrap() {
            String req = PromptBuilder.buildUntrustedTriggerRequest(trigger("telegram", "message"));
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
            String rendered = PromptBuilder.renderMemoryNotes(List.of(
                    MemoryNote.newBuilder().setContent("note one").build(),
                    MemoryNote.newBuilder().setContent("  ").build()));
            assertTrue(rendered.contains("<memory_notes>"));
            assertTrue(rendered.contains("- note one"));
            assertNull(PromptBuilder.renderMemoryNotes(List.of()));
        }
    }
}
