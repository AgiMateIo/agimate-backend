package ru.agimate.controlapi.service.subagent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.agimate.controlapi.abac.AccessDecision;
import ru.agimate.controlapi.abac.ConnectionAccessEvaluator;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.enums.AgentType;
import ru.agimate.controlapi.database.enums.PolicyKind;
import ru.agimate.controlapi.database.repositories.AgentRepository;
import ru.agimate.controlapi.service.AgentDeliveryService;
import ru.agimate.controlapi.service.team.TeamCircleService;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TeammateService")
class TeammateServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID TEAM_ID = UUID.randomUUID();
    private static final UUID CONNECTION_ID = UUID.randomUUID();

    @Mock private AgentRepository agentRepository;
    @Mock private AgentDeliveryService agentDeliveryService;
    @Mock private ConnectionAccessEvaluator accessEvaluator;

    private TeammateService service;
    private Agent asker;
    private Agent lawyer;

    @BeforeEach
    void setUp() {
        // The circle is real: its filtering is part of what these refusals prove.
        service = new TeammateService(new TeamCircleService(agentRepository, agentDeliveryService),
                agentRepository, agentDeliveryService, accessEvaluator);
        asker = agent("Менеджер", TEAM_ID);
        lawyer = agent("Юрист", TEAM_ID);
        lenient().when(agentDeliveryService.supportsPush(any(Agent.class))).thenReturn(true);
        lenient().when(accessEvaluator.evaluate(any(UUID.class), any(UUID.class), any(), any()))
                .thenReturn(AccessDecision.allow(null));
    }

    private static Agent agent(String name, UUID teamId) {
        return Agent.builder().id(UUID.randomUUID()).userId(USER_ID).name(name).description(name + " по делу")
                .type(AgentType.GENERIC).agenticTeamId(teamId).build();
    }

    private void bound(Agent... agents) {
        when(agentRepository.findBoundToConnection(USER_ID, CONNECTION_ID)).thenReturn(List.of(agents));
    }

    private Agent resolve(UUID calleeId) {
        return service.resolve(asker, CONNECTION_ID, calleeId, Map.of("fromAgentId", asker.getId().toString()));
    }

    @Test
    @DisplayName("кому можно поручить: привязанные участники команды с пушем, кроме себя")
    void eligible() {
        Agent other = agent("Чужой", UUID.randomUUID());
        Agent mcp = agent("MCP", TEAM_ID);
        when(agentDeliveryService.supportsPush(mcp)).thenReturn(false);
        bound(asker, lawyer, other, mcp);

        List<TeammateService.Teammate> teammates = service.eligible(asker, CONNECTION_ID);

        assertEquals(1, teammates.size());
        assertEquals(lawyer.getId(), teammates.get(0).id());
        assertEquals("Юрист", teammates.get(0).name());
    }

    @Test
    @DisplayName("адресат из команды, привязанный и без правила — поручение разрешено")
    void resolvesTeammate() {
        bound(asker, lawyer);

        assertEquals(lawyer, resolve(lawyer.getId()));
    }

    @Nested
    @DisplayName("отказы называют причину и кому можно поручить")
    class Refusals {

        @Test
        @DisplayName("без команды — некому")
        void noTeam() {
            asker.setAgenticTeamId(null);

            ConnectorException e = assertThrows(ConnectorException.class, () -> resolve(lawyer.getId()));
            assertTrue(e.getMessage().contains("not in a team"));
        }

        @Test
        @DisplayName("самому себе — через ask_subagent")
        void self() {
            ConnectorException e = assertThrows(ConnectorException.class, () -> resolve(asker.getId()));
            assertTrue(e.getMessage().contains("ask_subagent"));
        }

        @Test
        @DisplayName("агент другой команды — не в круге, в отказе список доступных")
        void otherTeam() {
            Agent other = agent("Чужой", UUID.randomUUID());
            bound(asker, lawyer, other);
            when(agentRepository.findById(other.getId())).thenReturn(Optional.of(other));

            ConnectorException e = assertThrows(ConnectorException.class, () -> resolve(other.getId()));
            assertTrue(e.getMessage().contains("Чужой is not in your team"));
            assertTrue(e.getMessage().contains("Юрист (" + lawyer.getId() + ")"));
        }

        @Test
        @DisplayName("участник команды без привязки к agents — не включил навык")
        void notBound() {
            bound(asker);
            when(agentRepository.findById(lawyer.getId())).thenReturn(Optional.of(lawyer));

            ConnectorException e = assertThrows(ConnectorException.class, () -> resolve(lawyer.getId()));
            assertTrue(e.getMessage().contains("has not enabled the agents skill"));
            assertTrue(e.getMessage().contains("Nobody else"));
        }

        @Test
        @DisplayName("чужой или несуществующий id — «нет такого агента», без раскрытия чужих")
        void unknownOrForeign() {
            bound(asker, lawyer);
            Agent foreign = Agent.builder().id(UUID.randomUUID()).userId(UUID.randomUUID()).name("Чужак").build();
            when(agentRepository.findById(foreign.getId())).thenReturn(Optional.of(foreign));

            ConnectorException e = assertThrows(ConnectorException.class, () -> resolve(foreign.getId()));
            assertTrue(e.getMessage().contains("No agent " + foreign.getId()));
        }

        @Test
        @DisplayName("правило адресата с params_filter по fromAgentId — отказ до маршрутизации")
        void calleeRuleDenies() {
            bound(asker, lawyer);
            when(accessEvaluator.evaluate(lawyer.getId(), CONNECTION_ID, PolicyKind.TRIGGER, "request_received"))
                    .thenReturn(AccessDecision.allow(null, Map.of("fromAgentId", UUID.randomUUID().toString())));

            ConnectorException e = assertThrows(ConnectorException.class, () -> resolve(lawyer.getId()));
            assertTrue(e.getMessage().contains("Юрист does not accept this request"));
        }
    }
}
