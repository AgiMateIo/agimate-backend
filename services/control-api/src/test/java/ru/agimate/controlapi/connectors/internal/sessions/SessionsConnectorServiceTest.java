package ru.agimate.controlapi.connectors.internal.sessions;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.agimate.controlapi.connectors.core.ConnectorEnv;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.connectors.core.dto.PromptBlock;
import ru.agimate.controlapi.database.entities.AgentRunTurn;
import ru.agimate.controlapi.database.entities.AgentSession;
import ru.agimate.controlapi.database.enums.AgentTurnRole;
import ru.agimate.controlapi.database.repositories.AgentRunRepository;
import ru.agimate.controlapi.database.repositories.AgentRunTurnRepository;
import ru.agimate.controlapi.database.repositories.AgentSessionRepository;
import ru.agimate.controlapi.service.session.compaction.SessionCompactionService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SessionsConnectorService — блок параллельных сессий, поиск и скрытая компакция")
class SessionsConnectorServiceTest {

    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final UUID SESSION_ID = UUID.randomUUID();

    @Mock private AgentRunTurnRepository turnRepository;
    @Mock private AgentSessionRepository sessionRepository;
    @Mock private AgentRunRepository runRepository;
    @Mock private SessionCompactionService compactionService;

    private SessionsConnectorService connector;

    @BeforeEach
    void setUp() {
        connector = new SessionsConnectorService(
                new SessionsToolService(turnRepository, sessionRepository, compactionService),
                sessionRepository, runRepository);
    }

    private static ConnectorEnv env(UUID sessionId) {
        return new ConnectorEnv("conn", UUID.randomUUID(), AGENT_ID, null, null, sessionId, Map.of(), null);
    }

    @Nested
    @DisplayName("search_messages")
    class Search {

        @Test
        @DisplayName("подстановочные знаки запроса — буквальные символы; фрагмент несёт заголовок и признак текущей")
        void escapesAndMaps() {
            AgentRunTurn turn = AgentRunTurn.builder().sessionId(SESSION_ID).role(AgentTurnRole.USER)
                    .text("скидка 50%_off до пятницы").build();
            turn.setCreatedAt(LocalDateTime.of(2026, 9, 1, 10, 0));
            when(turnRepository.searchText(AGENT_ID, "%50\\%\\_off%", 20)).thenReturn(List.of(turn));
            when(sessionRepository.findAllById(Set.of(SESSION_ID)))
                    .thenReturn(List.of(AgentSession.builder().id(SESSION_ID).title("Акции").build()));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> found = (List<Map<String, Object>>) connector
                    .executeTool(env(SESSION_ID), "search_messages", Map.of("query", "50%_off"))
                    .get("messages");

            assertEquals(1, found.size());
            assertEquals("Акции", found.get(0).get("title"));
            assertEquals(true, found.get(0).get("current"));
            assertEquals("user", found.get(0).get("role"));
            assertEquals("скидка 50%_off до пятницы", found.get(0).get("fragment"));
        }

        @Test
        @DisplayName("лимит упирается в потолок")
        void limitCapped() {
            when(turnRepository.searchText(eq(AGENT_ID), any(), eq(50))).thenReturn(List.of());
            when(sessionRepository.findAllById(any())).thenReturn(List.of());

            connector.executeTool(env(SESSION_ID), "search_messages", Map.of("query", "x", "limit", 500));

            verify(turnRepository).searchText(eq(AGENT_ID), any(), eq(50));
        }

        @Test
        @DisplayName("пустой запрос — отказ, а не выгрузка всей истории")
        void blankQueryRefused() {
            assertThrows(ConnectorException.class,
                    () -> connector.executeTool(env(SESSION_ID), "search_messages", Map.of("query", "  ")));
            verifyNoInteractions(turnRepository);
        }

        @Test
        @DisplayName("длинный текст режется вокруг совпадения")
        void fragmentAroundMatch() {
            String text = "a".repeat(1000) + "НАЙДИ" + "b".repeat(1000);

            String fragment = SessionsToolService.fragment(text, "найди");

            assertTrue(fragment.startsWith("…") && fragment.endsWith("…"), fragment);
            assertTrue(fragment.contains("НАЙДИ"), fragment);
            assertEquals(2 * SessionsToolService.FRAGMENT_RADIUS + "НАЙДИ".length() + 2, fragment.length());
        }
    }

    @Nested
    @DisplayName("compact")
    class Compact {

        @Test
        @DisplayName("модели не виден и через вызов тула недостижим")
        void hiddenFromModel() {
            assertFalse(connector.getTools().containsKey(SessionCompactionService.COMPACT_JOB));
            assertThrows(ConnectorException.class,
                    () -> connector.executeTool(env(SESSION_ID), SessionCompactionService.COMPACT_JOB, Map.of()));
            verifyNoInteractions(compactionService);
        }

        @Test
        @DisplayName("джоба вызывает уход за сессией; сбой не валит джобу — её перепланирует следующий ран")
        void jobSwallowsFailure() {
            when(compactionService.maintain(SESSION_ID)).thenThrow(new IllegalStateException("model down"));

            Map<String, Object> result = connector.executeJob(env(SESSION_ID), SessionCompactionService.COMPACT_JOB, Map.of());

            assertEquals(List.of(), result.get("done"));
            assertEquals("model down", result.get("error"));
        }
    }

    @Nested
    @DisplayName("блок sessions")
    class Block {

        @Test
        @DisplayName("других живых разговоров нет — блока нет")
        void noSiblingsNoBlock() {
            when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(AgentSession.builder().build()));
            when(sessionRepository.findLiveSiblings(eq(AGENT_ID), eq(SESSION_ID), any(), any())).thenReturn(List.of());

            assertEquals(List.of(), connector.promptBlocks(env(SESSION_ID)));
        }

        @Test
        @DisplayName("ран субагента чужие разговоры не видит")
        void childSeesNothing() {
            when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(
                    AgentSession.builder().parentSessionId(UUID.randomUUID()).build()));

            assertEquals(List.of(), connector.promptBlocks(env(SESSION_ID)));
        }

        @Test
        @DisplayName("строка на разговор: заголовок, канал, состояние; блок — в user-ход")
        void listsSiblings() {
            UUID busy = UUID.randomUUID();
            UUID quiet = UUID.randomUUID();
            LocalDateTime now = LocalDateTime.now();
            when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(AgentSession.builder().build()));
            when(sessionRepository.findLiveSiblings(eq(AGENT_ID), eq(SESSION_ID), any(), any())).thenReturn(List.of(
                    AgentSession.builder().id(busy).title("Отчёт").connectorCode("telegram").lastActivityAt(now).build(),
                    AgentSession.builder().id(quiet).connectorCode("webchat").lastActivityAt(now.minusMinutes(12)).build()));
            when(runRepository.findSessionsWithRunningRuns(List.of(busy, quiet))).thenReturn(List.of(busy));

            List<PromptBlock> blocks = connector.promptBlocks(env(SESSION_ID));

            assertEquals(1, blocks.size());
            assertEquals(PromptBlock.Placement.USER, blocks.get(0).placement());
            String content = blocks.get(0).content();
            assertTrue(content.contains("- «Отчёт» · telegram · answering now"), content);
            assertTrue(content.contains("- «untitled» · webchat · quiet for 12 min"), content);
        }
    }
}
