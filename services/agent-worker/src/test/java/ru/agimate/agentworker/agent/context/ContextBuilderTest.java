package ru.agimate.agentworker.agent.context;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import ru.agimate.agentworker.AgentMemory;
import ru.agimate.agentworker.AgentSpec;
import ru.agimate.agentworker.ConnectorToolSpec;
import ru.agimate.agentworker.MemoryNote;
import ru.agimate.agentworker.SkillRef;
import ru.agimate.agentworker.SkillSpec;
import ru.agimate.agentworker.agent.ToolRegistry;
import ru.agimate.agentworker.workers.run.PreparedContext;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextBuilderTest {

    private static ContextMaterials materials(List<SkillSpec> loadedSkills,
                                              List<MemoryNote> notes,
                                              List<ToolRegistry.ConnectorTools> tools) {
        AgentSpec spec = AgentSpec.newBuilder()
                .setAgentId("a-1").setName("Bot").setSystemPrompt("You are helpful.").build();
        AgentMemory memory = AgentMemory.newBuilder().setContent("remembered fact").setVersion(3).build();
        List<SkillRef> skills = List.of(SkillRef.newBuilder()
                .setSkillId("s1").setName("Board").addConnectorCodes("board").build());
        return new ContextMaterials(spec, null, skills, loadedSkills, memory, notes, tools);
    }

    @Nested
    @DisplayName("DIALOGUE profile")
    class Dialogue {
        @Test
        @DisplayName("composes the prompt without trigger guidance and with no tools/notes")
        void composes() {
            PreparedContext prepared = ContextBuilder.build(ContextProfile.DIALOGUE,
                    materials(List.of(), List.of(), List.of()));

            assertTrue(prepared.systemPrompt().contains("## Agent"));
            assertTrue(prepared.systemPrompt().contains("You are helpful."));
            assertTrue(prepared.systemPrompt().contains("## Skills"));
            assertFalse(prepared.systemPrompt().contains("## Обработка триггеров"));
            assertNull(prepared.memoryNotes());
            assertTrue(prepared.toolDefs().isEmpty());
            assertTrue(prepared.toolMap().isEmpty());
        }
    }

    @Nested
    @DisplayName("SYSTEM_TRIGGER profile")
    class SystemTrigger {
        @Test
        @DisplayName("appends trigger guidance and injects loaded skill bodies")
        void guidanceAndBodies() {
            SkillSpec loaded = SkillSpec.newBuilder()
                    .setSkillId("s1").setName("Board").setSkillMd("Act on board events.").build();
            PreparedContext prepared = ContextBuilder.build(ContextProfile.SYSTEM_TRIGGER,
                    materials(List.of(loaded), List.of(), List.of()));

            assertTrue(prepared.systemPrompt().endsWith(SystemPromptBuilder.TRIGGER_GUIDANCE));
            assertTrue(prepared.systemPrompt().contains("## Активный навык: Board"));
            assertTrue(prepared.systemPrompt().contains("Act on board events."));
        }
    }

    @Nested
    @DisplayName("tools and notes")
    class ToolsAndNotes {
        @Test
        @DisplayName("builds the tool registry from connector tools and renders memory notes")
        void toolsAndNotes() {
            ConnectorToolSpec toolSpec = ConnectorToolSpec.newBuilder()
                    .setName("get_tasks").setNamespace("board").setConnectionId("conn-1")
                    .setDescription("List tasks").build();
            PreparedContext prepared = ContextBuilder.build(ContextProfile.DIALOGUE,
                    materials(List.of(),
                            List.of(MemoryNote.newBuilder().setContent("note one").build()),
                            List.of(new ToolRegistry.ConnectorTools("board", List.of(toolSpec)))));

            assertEquals(1, prepared.toolDefs().size());
            assertEquals("board__get_tasks", prepared.toolDefs().get(0).name());
            ToolRegistry.BackendTool backend = prepared.toolMap().get("board__get_tasks");
            assertEquals("board", backend.connectorCode());
            assertEquals("get_tasks", backend.name());
            assertEquals("conn-1", backend.connectionId());
            assertTrue(prepared.memoryNotes().contains("- note one"));
        }
    }
}
