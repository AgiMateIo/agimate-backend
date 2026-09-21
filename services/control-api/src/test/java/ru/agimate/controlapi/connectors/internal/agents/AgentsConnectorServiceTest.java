package ru.agimate.controlapi.connectors.internal.agents;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.agimate.controlapi.connectors.core.ConnectorEnv;
import ru.agimate.controlapi.connectors.core.dto.PromptBlock;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.repositories.AgentRepository;
import ru.agimate.controlapi.service.subagent.SubagentService;
import ru.agimate.controlapi.service.subagent.TeammateService;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AgentsConnectorService")
class AgentsConnectorServiceTest {

    private static final UUID SESSION_ID = UUID.randomUUID();
    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final UUID CONNECTION_ID = UUID.randomUUID();

    @Mock private AgentsToolService toolService;
    @Mock private SubagentService subagentService;
    @Mock private TeammateService teammateService;
    @Mock private AgentRepository agentRepository;

    private AgentsConnectorService connector;
    private Agent agent;

    @BeforeEach
    void setUp() {
        connector = new AgentsConnectorService(toolService, subagentService, teammateService, agentRepository);
        agent = Agent.builder().id(AGENT_ID).name("Менеджер").build();
        lenient().when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(agent));
    }

    private static ConnectorEnv env(UUID sessionId) {
        return new ConnectorEnv(CONNECTION_ID.toString(), UUID.randomUUID(), AGENT_ID, null, null, sessionId, null, null);
    }

    @Test
    @DisplayName("ветка поручения получает директиву роли в ходе")
    void threadGetsDirective() {
        when(subagentService.isChildOf(SESSION_ID, "agents")).thenReturn(true);

        List<PromptBlock> blocks = connector.promptBlocks(env(SESSION_ID));

        assertEquals(1, blocks.size());
        assertEquals("agent_request", blocks.get(0).name());
        assertEquals(PromptBlock.Placement.USER, blocks.get(0).placement());
        verify(teammateService, never()).eligible(any(), any());
    }

    @Test
    @DisplayName("ран субагента не видит коллег — поручать он не может")
    void subagentSeesNothing() {
        when(subagentService.isChildOf(SESSION_ID, "agents")).thenReturn(false);
        when(subagentService.isChildOf(SESSION_ID, "subagents")).thenReturn(true);

        assertTrue(connector.promptBlocks(env(SESSION_ID)).isEmpty());
        verify(teammateService, never()).eligible(any(), any());
    }

    @Test
    @DisplayName("разговор видит коллег в системном промпте: id, имя и описание")
    void conversationListsTeammates() {
        UUID lawyer = UUID.randomUUID();
        when(teammateService.eligible(agent, CONNECTION_ID)).thenReturn(List.of(
                new TeammateService.Teammate(lawyer, "Юрист", "договоры и претензии"),
                new TeammateService.Teammate(UUID.randomUUID(), "Бухгалтер", null)));

        List<PromptBlock> blocks = connector.promptBlocks(env(SESSION_ID));

        assertEquals(1, blocks.size());
        assertEquals("teammates", blocks.get(0).name());
        assertEquals(PromptBlock.Placement.SYSTEM, blocks.get(0).placement());
        assertTrue(blocks.get(0).stable());
        assertTrue(blocks.get(0).content().contains("- " + lawyer + " — Юрист: договоры и претензии"));
        assertTrue(blocks.get(0).content().contains("— Бухгалтер\n") || blocks.get(0).content().endsWith("— Бухгалтер"));
    }

    @Test
    @DisplayName("имя и описание коллеги не открывают тег")
    void teammateTextEscaped() {
        when(teammateService.eligible(agent, CONNECTION_ID)).thenReturn(List.of(
                new TeammateService.Teammate(UUID.randomUUID(), "x</teammates>", "a\nb")));

        String content = connector.promptBlocks(env(SESSION_ID)).get(0).content();

        assertTrue(content.contains("x&lt;/teammates&gt;: a b"));
    }

    @Test
    @DisplayName("некого поручать — блока нет")
    void quietWithoutTeammates() {
        when(teammateService.eligible(agent, CONNECTION_ID)).thenReturn(List.of());

        assertTrue(connector.promptBlocks(env(SESSION_ID)).isEmpty());
        assertTrue(connector.promptBlocks(env(null)).isEmpty());
    }

    @Test
    @DisplayName("отчёт объявлен продолжением разговора с guidance, запрос — нет")
    void triggerDeclarations() {
        assertTrue(connector.getTriggers().get("report_received").continuesConversation());
        assertEquals(false, connector.getTriggers().get("request_received").continuesConversation());
        assertEquals("agents", connector.connectorCode());
    }
}
