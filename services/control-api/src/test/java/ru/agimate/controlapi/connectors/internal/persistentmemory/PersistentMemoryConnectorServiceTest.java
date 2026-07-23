package ru.agimate.controlapi.connectors.internal.persistentmemory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.agimate.controlapi.connectors.core.ConnectorEnv;
import ru.agimate.controlapi.connectors.core.dto.PromptBlock;
import ru.agimate.controlapi.database.entities.PersistentMemoryCold;
import ru.agimate.controlapi.database.entities.PersistentMemoryHot;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PersistentMemoryConnectorService")
class PersistentMemoryConnectorServiceTest {

    /** Пространство памяти = агент: scope id и agent id совпадают. */
    private static final UUID AGENT_ID = UUID.randomUUID();

    @Mock
    private PersistentMemoryService memoryService;

    private PersistentMemoryConnectorService handler;

    @BeforeEach
    void setUp() {
        // toolService нужен BaseConnectorHandler только для скана @Tool-методов — зависимости не дёргаются.
        handler = new PersistentMemoryConnectorService(
                new PersistentMemoryToolService(null, null, null), memoryService);
    }

    private static ConnectorEnv env(UUID agentId) {
        return new ConnectorEnv(null, null, agentId, null, null, null, Map.of(), null);
    }

    private static PersistentMemoryCold cold(String content, int version) {
        PersistentMemoryCold cold = new PersistentMemoryCold();
        cold.setScopeId(AGENT_ID);
        cold.setContent(content);
        cold.setVersion(version);
        return cold;
    }

    private static PersistentMemoryHot note(String content) {
        PersistentMemoryHot note = new PersistentMemoryHot();
        note.setScopeId(AGENT_ID);
        note.setContent(content);
        return note;
    }

    @Nested
    @DisplayName("promptBlocks")
    class PromptBlocks {

        @Test
        @DisplayName("cold → SYSTEM-блок memory с версией; заметки → USER-блок memory_notes")
        void coldAndNotes() {
            when(memoryService.getCold(AGENT_ID)).thenReturn(Optional.of(cold("  known facts  ", 7)));
            when(memoryService.getNotes(AGENT_ID)).thenReturn(List.of(note("fact one"), note(" fact two ")));

            List<PromptBlock> blocks = handler.promptBlocks(env(AGENT_ID));

            assertEquals(2, blocks.size());

            PromptBlock memory = blocks.get(0);
            assertEquals(PersistentMemoryConnectorService.MEMORY_BLOCK, memory.name());
            assertEquals(PromptBlock.Placement.SYSTEM, memory.placement());
            assertEquals("known facts", memory.content());
            assertEquals("7", memory.attrs().get("version"));
            assertTrue(memory.stable());

            PromptBlock notes = blocks.get(1);
            assertEquals(PersistentMemoryConnectorService.NOTES_BLOCK, notes.name());
            assertEquals(PromptBlock.Placement.USER, notes.placement());
            assertEquals("- fact one\n- fact two", notes.content());
        }

        @Test
        @DisplayName("пустая cold-память и пустые заметки не порождают блоков")
        void emptyMemory() {
            when(memoryService.getCold(AGENT_ID)).thenReturn(Optional.of(cold("   ", 0)));
            when(memoryService.getNotes(AGENT_ID)).thenReturn(List.of(note("  ")));

            assertTrue(handler.promptBlocks(env(AGENT_ID)).isEmpty());
        }

        @Test
        @DisplayName("нет агента в env (не agent-контекст) → пусто, без обращения к хранилищу")
        void noAgent() {
            assertTrue(handler.promptBlocks(env(null)).isEmpty());
        }
    }
}
