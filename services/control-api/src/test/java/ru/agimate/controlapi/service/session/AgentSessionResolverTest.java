package ru.agimate.controlapi.service.session;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.agimate.controlapi.database.entities.AgentSession;
import ru.agimate.controlapi.database.enums.AgentSessionScope;
import ru.agimate.controlapi.database.repositories.AgentSessionRepository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AgentSessionResolver — сессия коннекшена")
class AgentSessionResolverTest {

    private static final UUID AGENT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID CONNECTION = UUID.randomUUID();
    private static final String CONNECTOR = "board";

    @Mock
    private AgentSessionRepository agentSessionRepository;

    @InjectMocks
    private AgentSessionResolver resolver;

    private AgentSession session(UUID id) {
        AgentSession session = AgentSession.builder()
                .id(id)
                .scope(AgentSessionScope.CONNECTION)
                .agentId(AGENT)
                .userId(USER)
                .connectorCode(CONNECTOR)
                .connectionId(CONNECTION)
                .lastActivityAt(LocalDateTime.now())
                .build();
        return session;
    }

    @Test
    @DisplayName("живая сессия есть — берём её и двигаем активность, ничего не вставляя")
    void reusesLiveSession() {
        UUID id = UUID.randomUUID();
        when(agentSessionRepository.findLiveConnectionSession(AGENT, CONNECTION))
                .thenReturn(Optional.of(session(id)));

        assertEquals(id, resolver.forConnection(AGENT, USER, CONNECTOR, CONNECTION));

        verify(agentSessionRepository).touch(eq(id), any());
        verify(agentSessionRepository, never())
                .insertConnectionSession(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("живой нет — заводим и перечитываем")
    void createsWhenMissing() {
        UUID id = UUID.randomUUID();
        when(agentSessionRepository.findLiveConnectionSession(AGENT, CONNECTION))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(session(id)));
        when(agentSessionRepository.insertConnectionSession(eq(AGENT), eq(USER), eq(CONNECTOR),
                eq(CONNECTION), any())).thenReturn(1);

        assertEquals(id, resolver.forConnection(AGENT, USER, CONNECTOR, CONNECTION));
    }

    @Test
    @DisplayName("гонку выиграл сосед (0 строк) — берём его сессию, а не падаем")
    void losesTheRaceGracefully() {
        UUID winner = UUID.randomUUID();
        when(agentSessionRepository.findLiveConnectionSession(AGENT, CONNECTION))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(session(winner)));
        when(agentSessionRepository.insertConnectionSession(eq(AGENT), eq(USER), eq(CONNECTOR),
                eq(CONNECTION), any())).thenReturn(0);

        assertEquals(winner, resolver.forConnection(AGENT, USER, CONNECTOR, CONNECTION));
    }
}
