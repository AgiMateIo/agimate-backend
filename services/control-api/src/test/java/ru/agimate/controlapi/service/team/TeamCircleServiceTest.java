package ru.agimate.controlapi.service.team;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.enums.AgentType;
import ru.agimate.controlapi.database.repositories.AgentRepository;
import ru.agimate.controlapi.service.AgentDeliveryService;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TeamCircleService")
class TeamCircleServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID TEAM_ID = UUID.randomUUID();
    private static final UUID CONNECTION_ID = UUID.randomUUID();

    @Mock private AgentRepository agentRepository;
    @Mock private AgentDeliveryService agentDeliveryService;

    private TeamCircleService service;

    @BeforeEach
    void setUp() {
        service = new TeamCircleService(agentRepository, agentDeliveryService);
        lenient().when(agentDeliveryService.supportsPush(any(Agent.class))).thenReturn(true);
    }

    private static Agent agent(UUID teamId) {
        return Agent.builder().id(UUID.randomUUID()).userId(USER_ID).type(AgentType.GENERIC).agenticTeamId(teamId).build();
    }

    @Test
    @DisplayName("членство: та же команда; агент без команды и чужая команда — нет")
    void membership() {
        assertTrue(service.isMember(TEAM_ID, agent(TEAM_ID)));
        assertFalse(service.isMember(TEAM_ID, agent(UUID.randomUUID())));
        assertFalse(service.isMember(TEAM_ID, agent(null)));
        assertFalse(service.isMember(null, agent(null)));
    }

    @Test
    @DisplayName("участники подключения: привязанные участники команды с пушем")
    void participants() {
        Agent member = agent(TEAM_ID);
        Agent outsider = agent(UUID.randomUUID());
        Agent unreachable = agent(TEAM_ID);
        when(agentDeliveryService.supportsPush(unreachable)).thenReturn(false);
        when(agentRepository.findBoundToConnection(USER_ID, CONNECTION_ID))
                .thenReturn(List.of(member, outsider, unreachable));

        assertEquals(List.of(member), service.participants(USER_ID, TEAM_ID, CONNECTION_ID));
    }

    @Test
    @DisplayName("без команды участников нет и база не спрашивается")
    void noTeamNoParticipants() {
        assertTrue(service.participants(USER_ID, null, CONNECTION_ID).isEmpty());
        verifyNoInteractions(agentRepository);
    }
}
