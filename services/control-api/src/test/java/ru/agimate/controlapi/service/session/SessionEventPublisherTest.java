package ru.agimate.controlapi.service.session;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.agimate.controlapi.controller.manage.dto.session.SessionResponse;
import ru.agimate.controlapi.controller.manage.dto.webchat.WebchatContactResponse;
import ru.agimate.controlapi.database.entities.AgentSession;
import ru.agimate.controlapi.database.repositories.AgentSessionRepository;
import ru.agimate.controlapi.service.centrifugo.CentrifugoService;
import ru.agimate.controlapi.service.webchat.ContactRows;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SessionEventPublisher — строка сессии в user:{userId}")
class SessionEventPublisherTest {

    private static final UUID SESSION_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final String CHANNEL = "user:" + USER_ID;

    @Mock private AgentSessionRepository agentSessionRepository;
    @Mock private SessionRows sessionRows;
    @Mock private ContactRows contactRows;
    @Mock private CentrifugoService centrifugoService;
    @InjectMocks private SessionEventPublisher publisher;

    private final SessionResponse row = mock(SessionResponse.class);

    private AgentSession session(String connectorCode) {
        AgentSession session = AgentSession.builder()
                .id(SESSION_ID)
                .userId(USER_ID)
                .agentId(AGENT_ID)
                .connectorCode(connectorCode)
                .build();
        when(agentSessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(sessionRows.of(session)).thenReturn(row);
        return session;
    }

    @Test
    @DisplayName("новая сессия — session.created со строкой листинга и тегами")
    void createdCarriesRow() {
        session("telegram");

        publisher.onSessionChanged(SessionChanged.created(SESSION_ID));

        verify(centrifugoService).publishMessage(CHANNEL, SessionEventPublisher.CREATED, row,
                Map.of("entity", "session", "agentId", AGENT_ID.toString()));
        verifyNoInteractions(contactRows);
    }

    @Test
    @DisplayName("сессия веб-чата — вслед за строкой сессии строка контакта")
    void webchatAlsoMovesContact() {
        session("webchat");
        WebchatContactResponse contact = mock(WebchatContactResponse.class);
        when(contactRows.find(AGENT_ID)).thenReturn(Optional.of(contact));

        publisher.onSessionChanged(SessionChanged.updated(SESSION_ID));

        verify(centrifugoService).publishMessage(CHANNEL, SessionEventPublisher.UPDATED, row,
                Map.of("entity", "session", "agentId", AGENT_ID.toString()));
        verify(centrifugoService).publishMessage(CHANNEL, SessionEventPublisher.AGENT_UPDATED, contact,
                Map.of("entity", "webchat.agent", "agentId", AGENT_ID.toString()));
    }

    @Test
    @DisplayName("сессии нет — публиковать нечего")
    void missingSessionIsNoop() {
        when(agentSessionRepository.findById(SESSION_ID)).thenReturn(Optional.empty());

        publisher.onSessionChanged(SessionChanged.updated(SESSION_ID));

        verifyNoInteractions(centrifugoService);
    }

    @Test
    @DisplayName("Centrifugo упал — исключение не выходит наружу")
    void failureSwallowed() {
        session("telegram");
        doThrow(new RuntimeException("down")).when(centrifugoService)
                .publishMessage(anyString(), anyString(), any(), anyMap());

        assertDoesNotThrow(() -> publisher.onSessionChanged(SessionChanged.updated(SESSION_ID)));
        verify(centrifugoService).publishMessage(eq(CHANNEL), anyString(), any(), anyMap());
    }
}
