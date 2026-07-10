package ru.agimate.controlapi.connectors.internal.persistentmemory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.agimate.controlapi.connectors.core.ConnectorContext;
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

    private static final UUID CONNECTION_ID = UUID.randomUUID();
    private static final UUID SCOPE_ID = UUID.randomUUID();

    @Mock
    private PersistentMemoryService memoryService;

    private PersistentMemoryConnectorService handler;

    @BeforeEach
    void setUp() {
        // toolService нужен BaseConnectorHandler только для скана @Tool-методов — зависимости не дёргаются.
        handler = new PersistentMemoryConnectorService(
                new PersistentMemoryToolService(null, null, null, null), memoryService);
    }

    private static ConnectorContext context(String connectionId) {
        return new ConnectorContext(connectionId, null, null, null, Map.of(), null);
    }

    private static PersistentMemoryCold cold(String content, int version) {
        PersistentMemoryCold cold = new PersistentMemoryCold();
        cold.setScopeId(SCOPE_ID);
        cold.setContent(content);
        cold.setVersion(version);
        return cold;
    }

    private static PersistentMemoryHot note(String content) {
        PersistentMemoryHot note = new PersistentMemoryHot();
        note.setScopeId(SCOPE_ID);
        note.setContent(content);
        return note;
    }

    @Nested
    @DisplayName("promptBlocks")
    class PromptBlocks {

        @Test
        @DisplayName("cold → SYSTEM-блок memory с версией; заметки → USER-блок memory_notes")
        void coldAndNotes() {
            when(memoryService.scopeIdForConnection(CONNECTION_ID)).thenReturn(Optional.of(SCOPE_ID));
            when(memoryService.getCold(SCOPE_ID)).thenReturn(Optional.of(cold("  known facts  ", 7)));
            when(memoryService.getNotes(SCOPE_ID)).thenReturn(List.of(note("fact one"), note(" fact two ")));

            List<PromptBlock> blocks = handler.promptBlocks(context(CONNECTION_ID.toString()));

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
            when(memoryService.scopeIdForConnection(CONNECTION_ID)).thenReturn(Optional.of(SCOPE_ID));
            when(memoryService.getCold(SCOPE_ID)).thenReturn(Optional.of(cold("   ", 0)));
            when(memoryService.getNotes(SCOPE_ID)).thenReturn(List.of(note("  ")));

            assertTrue(handler.promptBlocks(context(CONNECTION_ID.toString())).isEmpty());
        }

        @Test
        @DisplayName("нет scope у connection → пусто")
        void noScope() {
            when(memoryService.scopeIdForConnection(CONNECTION_ID)).thenReturn(Optional.empty());

            assertTrue(handler.promptBlocks(context(CONNECTION_ID.toString())).isEmpty());
        }

        @Test
        @DisplayName("некорректный connectionId → пусто, без обращения к хранилищу")
        void invalidConnectionId() {
            assertTrue(handler.promptBlocks(context("not-a-uuid")).isEmpty());
            assertTrue(handler.promptBlocks(context(null)).isEmpty());
        }
    }
}
