package ru.agimate.controlapi.service.session;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.agimate.controlapi.realtime.RealtimeEvent.SessionChanged;
import ru.agimate.controlapi.realtime.RealtimePublisher;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.controlapi.database.entities.AgentSession;
import ru.agimate.controlapi.database.enums.AgentSessionScope;
import ru.agimate.controlapi.database.repositories.AgentSessionRepository;
import ru.agimate.controlapi.database.repositories.WebchatMessageRepository;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AgentSessionService")
class AgentSessionServiceTest {

    private static final UUID SESSION_ID = UUID.randomUUID();

    @Mock
    private AgentSessionRepository agentSessionRepository;
    @Mock
    private WebchatMessageRepository webchatMessageRepository;
    @Mock
    private RealtimePublisher realtime;

    private AgentSessionService agentSessionService;

    private AgentSession session;

    @BeforeEach
    void setUp() {
        agentSessionService = new AgentSessionService(agentSessionRepository, webchatMessageRepository, realtime);
        session = AgentSession.builder().id(SESSION_ID).build();
    }

    @Nested
    @DisplayName("markRead — указатель прочтения")
    class MarkRead {

        @Test
        @DisplayName("указанное сообщение двигает указатель")
        void advancesToGivenMessage() {
            UUID messageRowId = UUID.randomUUID();
            when(webchatMessageRepository.existsByIdAndSessionId(messageRowId, SESSION_ID)).thenReturn(true);

            agentSessionService.markRead(SESSION_ID, messageRowId);

            verify(agentSessionRepository).advanceReadPointer(eq(SESSION_ID), eq(messageRowId), any());
        }

        @Test
        @DisplayName("сдвинутый указатель объявляется событием сессии")
        void movedPointerAnnounced() {
            UUID messageRowId = UUID.randomUUID();
            when(webchatMessageRepository.existsByIdAndSessionId(messageRowId, SESSION_ID)).thenReturn(true);
            when(agentSessionRepository.advanceReadPointer(eq(SESSION_ID), eq(messageRowId), any())).thenReturn(1);

            agentSessionService.markRead(SESSION_ID, messageRowId);

            verify(realtime).publish(SessionChanged.updated(SESSION_ID));
        }

        @Test
        @DisplayName("указатель уже стоял там — события нет")
        void unmovedPointerSilent() {
            UUID messageRowId = UUID.randomUUID();
            when(webchatMessageRepository.existsByIdAndSessionId(messageRowId, SESSION_ID)).thenReturn(true);
            when(agentSessionRepository.advanceReadPointer(eq(SESSION_ID), eq(messageRowId), any())).thenReturn(0);

            agentSessionService.markRead(SESSION_ID, messageRowId);

            verify(realtime, never()).publish(any());
        }

        @Test
        @DisplayName("сообщение не из этой сессии — 400, указатель не трогаем")
        void foreignMessageRejected() {
            UUID messageRowId = UUID.randomUUID();
            when(webchatMessageRepository.existsByIdAndSessionId(messageRowId, SESSION_ID)).thenReturn(false);

            assertThrows(BadRequestStatusException.class,
                    () -> agentSessionService.markRead(SESSION_ID, messageRowId));
            verify(agentSessionRepository, never()).advanceReadPointer(any(), any(), any());
        }

        @Test
        @DisplayName("без сообщения — прочитано до последнего в сессии")
        void readsThroughLatest() {
            UUID lastMessageRowId = UUID.randomUUID();
            when(webchatMessageRepository.findLastMessageId(SESSION_ID))
                    .thenReturn(Optional.of(lastMessageRowId));

            agentSessionService.markRead(SESSION_ID, null);

            verify(agentSessionRepository).advanceReadPointer(eq(SESSION_ID), eq(lastMessageRowId), any());
        }

        @Test
        @DisplayName("сессия не вебчата — показывать нечего, но и не ошибка")
        void nothingShownYet() {
            when(webchatMessageRepository.findLastMessageId(SESSION_ID)).thenReturn(Optional.empty());

            agentSessionService.markRead(SESSION_ID, null);

            verify(agentSessionRepository, never()).advanceReadPointer(any(), any(), any());
        }
    }

    @Nested
    @DisplayName("rename — явное переименование")
    class Rename {

        @Test
        @DisplayName("заголовок обрезается по краям и сохраняется")
        void trimsAndSaves() {
            when(agentSessionRepository.save(session)).thenReturn(session);

            AgentSession renamed = agentSessionService.rename(session, "  Отпуск в июле  ");

            assertEquals("Отпуск в июле", renamed.getTitle());
            verify(realtime).publish(SessionChanged.updated(SESSION_ID));
        }

        @Test
        @DisplayName("длиннее предела — 400, а не молчаливая обрезка")
        void rejectsTooLong() {
            String title = "я".repeat(AgentSessionService.TITLE_MAX_LENGTH + 1);

            assertThrows(BadRequestStatusException.class, () -> agentSessionService.rename(session, title));
            verify(agentSessionRepository, never()).save(any());
        }

        @Test
        @DisplayName("из одних пробелов — 400")
        void rejectsBlank() {
            assertThrows(BadRequestStatusException.class, () -> agentSessionService.rename(session, "   "));
            verify(agentSessionRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("forConnection — сессия коннекции")
    class ForConnection {

        private static final UUID AGENT = UUID.randomUUID();
        private static final UUID USER = UUID.randomUUID();
        private static final UUID CONNECTION = UUID.randomUUID();

        private AgentSession live(UUID id) {
            return AgentSession.builder().id(id).scope(AgentSessionScope.CONNECTION)
                    .agentId(AGENT).userId(USER).connectorCode("board").connectionId(CONNECTION).build();
        }

        @Test
        @DisplayName("живая сессия есть — берём её и двигаем активность, ничего не вставляя и не объявляя")
        void reusesLiveSession() {
            UUID id = UUID.randomUUID();
            when(agentSessionRepository.findLiveConnectionSession(AGENT, CONNECTION)).thenReturn(Optional.of(live(id)));

            assertEquals(id, agentSessionService.forConnection(AGENT, USER, "board", CONNECTION));

            verify(agentSessionRepository).touch(eq(id), any());
            verify(agentSessionRepository, never()).insertConnectionSession(any(), any(), any(), any(), any());
            verifyNoInteractions(realtime);
        }

        @Test
        @DisplayName("живой нет — заводим, перечитываем и объявляем новую сессию")
        void createsWhenMissing() {
            UUID id = UUID.randomUUID();
            when(agentSessionRepository.findLiveConnectionSession(AGENT, CONNECTION))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(live(id)));
            when(agentSessionRepository.insertConnectionSession(eq(AGENT), eq(USER), eq("board"), eq(CONNECTION), any()))
                    .thenReturn(1);

            assertEquals(id, agentSessionService.forConnection(AGENT, USER, "board", CONNECTION));
            verify(realtime).publish(SessionChanged.created(id));
        }

        @Test
        @DisplayName("гонку выиграл сосед (0 строк) — берём его сессию; объявляет её он, а не мы")
        void losesTheRaceGracefully() {
            UUID winner = UUID.randomUUID();
            when(agentSessionRepository.findLiveConnectionSession(AGENT, CONNECTION))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(live(winner)));
            when(agentSessionRepository.insertConnectionSession(eq(AGENT), eq(USER), eq("board"), eq(CONNECTION), any()))
                    .thenReturn(0);

            assertEquals(winner, agentSessionService.forConnection(AGENT, USER, "board", CONNECTION));
            verifyNoInteractions(realtime);
        }
    }

    @Nested
    @DisplayName("writeGeneratedTitle — заголовок от компакции")
    class GeneratedTitle {

        @Test
        @DisplayName("заголовок лёг — объявляем")
        void writtenAnnounced() {
            when(agentSessionRepository.writeGeneratedTitle(eq(SESSION_ID), eq("T"), any())).thenReturn(1);

            agentSessionService.writeGeneratedTitle(SESSION_ID, "T");

            verify(realtime).publish(SessionChanged.updated(SESSION_ID));
        }

        @Test
        @DisplayName("пользователь переименовал сам — заголовок не лёг, события нет")
        void userTitleKept() {
            when(agentSessionRepository.writeGeneratedTitle(eq(SESSION_ID), eq("T"), any())).thenReturn(0);

            agentSessionService.writeGeneratedTitle(SESSION_ID, "T");

            verifyNoInteractions(realtime);
        }
    }

    @Test
    @DisplayName("touch — активность двигается молча: у сессии коннекции она растёт с каждым триггером")
    void touchIsSilent() {
        agentSessionService.touch(SESSION_ID);

        verify(agentSessionRepository).touch(eq(SESSION_ID), any());
        verifyNoInteractions(realtime);
    }
}
