package ru.agimate.controlapi.service.session;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.controlapi.database.entities.AgentSession;
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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AgentSessionService")
class AgentSessionServiceTest {

    private static final UUID SESSION_ID = UUID.randomUUID();

    @Mock
    private AgentSessionRepository agentSessionRepository;
    @Mock
    private WebchatMessageRepository webchatMessageRepository;

    private AgentSessionService agentSessionService;

    private AgentSession session;

    @BeforeEach
    void setUp() {
        agentSessionService = new AgentSessionService(agentSessionRepository, webchatMessageRepository);
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
}
