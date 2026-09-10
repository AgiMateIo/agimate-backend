package ru.agimate.controlapi.connectors.internal.persistentmemory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import ru.agimate.controlapi.connectors.core.ConnectorEnv;
import ru.agimate.controlapi.database.enums.ChannelSessionMessageKind;
import ru.agimate.controlapi.database.projections.SessionNoteLineProjection;
import ru.agimate.controlapi.database.repositories.ChannelSessionMessageRepository;
import ru.agimate.controlapi.service.trigger.Trigger;
import ru.agimate.controlapi.service.trigger.TriggerRouterService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("persist-memory: daily note job")
class PersistentMemoryDailyJobTest {

    private static final UUID CONNECTION_ID = UUID.randomUUID();
    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final UUID SESSION_ID = UUID.randomUUID();

    @Mock
    private PersistentMemoryService memoryService;
    @Mock
    private TriggerRouterService triggerRouterService;
    @Mock
    private ChannelSessionMessageRepository messageRepository;

    private PersistentMemoryConnectorService handler;

    @BeforeEach
    void setUp() {
        handler = new PersistentMemoryConnectorService(
                new PersistentMemoryToolService(memoryService, triggerRouterService, messageRepository),
                memoryService);
        when(memoryService.boundAgents(CONNECTION_ID)).thenReturn(List.of(AGENT_ID));
    }

    /** The job reads its env from the ThreadLocal, and only the handler may bind it — hence the dispatch. */
    private void runDaily() {
        ConnectorEnv env = new ConnectorEnv(
                CONNECTION_ID.toString(), UUID.randomUUID(), null, null, null, null, Map.of(), null);
        handler.executeJob(env, "daily", Map.of());
    }

    private static SessionNoteLineProjection line(ChannelSessionMessageKind kind, String message) {
        return new SessionNoteLineProjection() {
            @Override
            public ChannelSessionMessageKind getKind() {
                return kind;
            }

            @Override
            public String getMessage() {
                return message;
            }
        };
    }

    private void sessionWithLines(List<SessionNoteLineProjection> newestFirst) {
        when(messageRepository.findSessionIdsByAgentSince(eq(AGENT_ID), any()))
                .thenReturn(List.of(SESSION_ID));
        when(messageRepository.findNoteLinesBySessionSince(eq(SESSION_ID), any(), any()))
                .thenReturn(newestFirst);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> capturedMessages() {
        ArgumentCaptor<Trigger> captor = ArgumentCaptor.forClass(Trigger.class);
        verify(triggerRouterService).routeTrigger(any(), captor.capture());
        return (List<Map<String, Object>>) captor.getValue().data().get("messages");
    }

    @Nested
    @DisplayName("query bounds")
    class QueryBounds {

        @Test
        @DisplayName("the same window as the session lookup, capped at 500 lines")
        void windowAndCap() {
            sessionWithLines(List.of(line(ChannelSessionMessageKind.ANSWER, "hi")));

            runDaily();

            ArgumentCaptor<LocalDateTime> sessionsSince = ArgumentCaptor.forClass(LocalDateTime.class);
            verify(messageRepository).findSessionIdsByAgentSince(eq(AGENT_ID), sessionsSince.capture());

            ArgumentCaptor<LocalDateTime> linesSince = ArgumentCaptor.forClass(LocalDateTime.class);
            ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
            verify(messageRepository).findNoteLinesBySessionSince(
                    eq(SESSION_ID), linesSince.capture(), pageable.capture());

            // The two queries have to agree on the bound: a session selected by the first must not come
            // back empty from the second.
            assertEquals(sessionsSince.getValue(), linesSince.getValue());
            assertEquals(PageRequest.of(0, 500), pageable.getValue());
        }

        @Test
        @DisplayName("a session with nothing inside the window emits no trigger")
        void emptyWindow() {
            sessionWithLines(List.of());

            runDaily();

            verify(triggerRouterService, never()).routeTrigger(any(), any());
        }
    }

    @Nested
    @DisplayName("payload")
    class Payload {

        @Test
        @DisplayName("newest-first rows reach the agent in chronological order")
        void chronological() {
            sessionWithLines(List.of(
                    line(ChannelSessionMessageKind.ANSWER, "third"),
                    line(ChannelSessionMessageKind.INBOUND, "second"),
                    line(ChannelSessionMessageKind.ANSWER, "first")));

            runDaily();

            assertEquals(List.of("first", "second", "third"),
                    capturedMessages().stream().map(m -> m.get("text")).toList());
        }

        @Test
        @DisplayName("over the char budget the oldest lines are dropped, the newest stay")
        void budgetDropsOldest() {
            String chunk = "x".repeat(25_000);
            sessionWithLines(List.of(
                    line(ChannelSessionMessageKind.ANSWER, chunk),
                    line(ChannelSessionMessageKind.INBOUND, chunk),
                    line(ChannelSessionMessageKind.ANSWER, chunk),
                    line(ChannelSessionMessageKind.INBOUND, "oldest, must be dropped")));

            runDaily();

            List<Map<String, Object>> messages = capturedMessages();
            assertEquals(2, messages.size());
            assertTrue(messages.stream().noneMatch(m -> "oldest, must be dropped".equals(m.get("text"))));
        }

        @Test
        @DisplayName("a single message over the budget shortens the request, it does not empty it")
        void budgetNeverEmpties() {
            sessionWithLines(List.of(
                    line(ChannelSessionMessageKind.ANSWER, "y".repeat(200_000)),
                    line(ChannelSessionMessageKind.INBOUND, "older")));

            runDaily();

            List<Map<String, Object>> messages = capturedMessages();
            assertEquals(1, messages.size());
            assertEquals(200_000, ((String) messages.getFirst().get("text")).length());
        }

        @Test
        @DisplayName("a row with no text is passed through, as it was before the window was bounded")
        void nullText() {
            sessionWithLines(List.of(
                    line(ChannelSessionMessageKind.ANSWER, "said"),
                    line(ChannelSessionMessageKind.INBOUND, null)));

            runDaily();

            List<Map<String, Object>> messages = capturedMessages();
            assertEquals(2, messages.size());
            assertNull(messages.getFirst().get("text"));
        }
    }
}
