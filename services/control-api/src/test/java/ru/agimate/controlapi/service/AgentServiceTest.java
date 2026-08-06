package ru.agimate.controlapi.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.controlapi.controller.manage.dto.UpdateAgentRequest;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.entities.Channel;
import ru.agimate.controlapi.database.enums.AgentType;
import ru.agimate.controlapi.database.repositories.AgentRepository;
import ru.agimate.controlapi.database.repositories.AgentSkillRepository;
import ru.agimate.controlapi.database.repositories.ChannelRepository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AgentService.update — смена типа агента")
class AgentServiceTest {

    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();

    @Mock
    private AgentRepository agentRepository;
    @Mock
    private ChannelRepository channelRepository;
    @Mock
    private AgentDeliveryService agentDeliveryService;
    @Mock
    private AgentSkillRepository agentSkillRepository;
    @Mock
    private AgentLlmService agentLlmService;

    @InjectMocks
    private AgentService service;

    private final Agent agent = Agent.builder()
            .id(AGENT_ID).userId(USER_ID).name("agent").type(AgentType.CENTRIFUGO)
            // The response masks the key id, so it has to look like one
            .keyId("Z3h5YWJjZGVl").keyHash("hash").build();

    @BeforeEach
    void setUp() {
        when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(agent));
        when(agentRepository.save(any(Agent.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(agentSkillRepository.findSkillSummariesByAgentIdIn(any())).thenReturn(List.of());
        when(agentLlmService.listForAgents(anyList())).thenReturn(Map.of());
        when(agentDeliveryService.supportsPush(AgentType.MCP)).thenReturn(false);
        when(agentDeliveryService.supportsPush(AgentType.CENTRIFUGO)).thenReturn(true);
    }

    private UpdateAgentRequest toType(AgentType type) {
        return new UpdateAgentRequest("agent", null, null, type, null, null, null);
    }

    @Test
    @DisplayName("на тип без доставки при живом канале → 400, тип не меняется")
    void refusesWhenChannelsWouldBeOrphaned() {
        when(channelRepository.findByAgentIdAndDeletedAtIsNullOrderByCreatedAtDesc(AGENT_ID))
                .thenReturn(List.of(mock(Channel.class)));

        assertThrows(BadRequestStatusException.class,
                () -> service.update(AGENT_ID, USER_ID, toType(AgentType.MCP)));

        verify(agentRepository, never()).save(any());
        assertEquals(AgentType.CENTRIFUGO, agent.getType());
    }

    @Test
    @DisplayName("каналов нет → на тип без доставки переключиться можно")
    void allowsWhenThereAreNoChannels() {
        when(channelRepository.findByAgentIdAndDeletedAtIsNullOrderByCreatedAtDesc(AGENT_ID))
                .thenReturn(List.of());

        service.update(AGENT_ID, USER_ID, toType(AgentType.MCP));

        assertEquals(AgentType.MCP, agent.getType());
    }

    @Test
    @DisplayName("каналы есть, но тип с доставкой → проверка не мешает")
    void allowsPushableTypeWithChannels() {
        when(channelRepository.findByAgentIdAndDeletedAtIsNullOrderByCreatedAtDesc(AGENT_ID))
                .thenReturn(List.of(mock(Channel.class)));

        service.update(AGENT_ID, USER_ID, toType(AgentType.CENTRIFUGO));

        assertEquals(AgentType.CENTRIFUGO, agent.getType());
    }
}
