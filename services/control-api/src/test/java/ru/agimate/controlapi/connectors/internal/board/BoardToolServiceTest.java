package ru.agimate.controlapi.connectors.internal.board;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import ru.agimate.controlapi.connectors.core.ConnectorEnv;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.entities.StoredFile;
import ru.agimate.controlapi.database.repositories.AgentRepository;
import ru.agimate.controlapi.database.repositories.AgenticTeamRepository;
import ru.agimate.controlapi.database.repositories.BoardRepository;
import ru.agimate.controlapi.service.board.BoardService;
import ru.agimate.controlapi.service.dto.board.BoardTaskCommentResponse;
import ru.agimate.controlapi.storage.FileStorageService;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("BoardToolService")
class BoardToolServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final UUID TASK_ID = UUID.randomUUID();
    private static final String REAL_FILE = "agf_019f8948-b2e5-7ad5-82ee-2c4f6f6bd349";

    private final BoardService boardService = mock(BoardService.class);
    private final AgentRepository agentRepository = mock(AgentRepository.class);
    private final FileStorageService fileStorageService = mock(FileStorageService.class);
    private final BoardConnectorService handler = new BoardConnectorService(new BoardToolService(
            boardService, agentRepository, mock(AgenticTeamRepository.class),
            mock(BoardRepository.class), fileStorageService));

    private static ConnectorEnv env() {
        return new ConnectorEnv(null, USER_ID, AGENT_ID, null, null, Map.of(), null);
    }

    private void stubAgent() {
        when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(
                Agent.builder().id(AGENT_ID).userId(USER_ID).name("Bot").build()));
    }

    private Map<String, Object> createComment(String content) {
        return handler.executeTool(env(), "create_comment",
                Map.of("taskId", TASK_ID.toString(), "content", content));
    }

    @Nested
    @DisplayName("create_comment: валидация agf-ссылок")
    class CommentFileRefs {

        @Test
        @DisplayName("ссылка на существующий файл проходит")
        void validRefPasses() {
            stubAgent();
            when(fileStorageService.findReadable(USER_ID, REAL_FILE))
                    .thenReturn(Optional.of(new StoredFile()));
            when(boardService.createComment(eq(null), eq(TASK_ID), eq(USER_ID), any()))
                    .thenReturn(new BoardTaskCommentResponse(UUID.randomUUID(), AGENT_ID, "c", null));

            Map<String, Object> result = createComment("Готово: [[attach:" + REAL_FILE + "]]");

            assertTrue(result.containsKey("comment"));
        }

        @Test
        @DisplayName("ссылка на несуществующий файл → ошибка агенту, комментарий не создан")
        void unknownRefRejected() {
            stubAgent();
            when(fileStorageService.findReadable(USER_ID, REAL_FILE)).thenReturn(Optional.empty());

            ConnectorException e = assertThrows(ConnectorException.class,
                    () -> createComment("Итоговый файл: " + REAL_FILE));

            assertTrue(e.getMessage().contains(REAL_FILE));
            verify(boardService, never()).createComment(any(), any(), any(), any());
        }

        @Test
        @DisplayName("выдуманный не-uuid id (agf_hermit_ix.png) → ошибка")
        void garbageRefRejected() {
            stubAgent();

            ConnectorException e = assertThrows(ConnectorException.class,
                    () -> createComment("Файл: agf_hermit_ix.png"));

            assertTrue(e.getMessage().contains("agf_hermit_ix.png"));
            verify(boardService, never()).createComment(any(), any(), any(), any());
        }

        @Test
        @DisplayName("комментарий без ссылок не трогает хранилище")
        void noRefsNoStorageLookup() {
            stubAgent();
            when(boardService.createComment(eq(null), eq(TASK_ID), eq(USER_ID), any()))
                    .thenReturn(new BoardTaskCommentResponse(UUID.randomUUID(), AGENT_ID, "c", null));

            Map<String, Object> result = createComment("Беру в работу");

            assertEquals(1, ((Map<?, ?>) result).size());
            verifyNoInteractions(fileStorageService);
        }
    }
}
