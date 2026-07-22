package ru.agimate.controlapi.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.controlapi.connectors.core.ConnectorRegistry;
import ru.agimate.controlapi.connectors.core.InternalConnectorHandler;
import ru.agimate.controlapi.database.entities.AgentConnection;
import ru.agimate.controlapi.database.entities.AgentSkill;
import ru.agimate.controlapi.database.entities.Channel;
import ru.agimate.controlapi.database.entities.Connection;
import ru.agimate.controlapi.database.entities.Skill;
import ru.agimate.controlapi.database.repositories.AgentConnectionRepository;
import ru.agimate.controlapi.database.repositories.AgentSkillRepository;
import ru.agimate.controlapi.database.repositories.ChannelRepository;
import ru.agimate.controlapi.database.repositories.ConnectionRepository;
import ru.agimate.controlapi.database.repositories.SkillRepository;
import ru.agimate.controlapi.service.connection.ConnectionBindingService;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AgentSkillPolicyService.applyDiff (реконсиляция)")
class AgentSkillPolicyServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final UUID SKILL_ID = UUID.randomUUID();
    private static final UUID CONNECTION_ID = UUID.randomUUID();

    @Mock
    private AgentSkillRepository agentSkillRepository;
    @Mock
    private SkillRepository skillRepository;
    @Mock
    private AgentConnectionRepository agentConnectionRepository;
    @Mock
    private ConnectionRepository connectionRepository;
    @Mock
    private ChannelRepository channelRepository;
    @Mock
    private ConnectionBindingService connectionBindingService;
    @Mock
    private ConnectorRegistry connectorRegistry;

    @InjectMocks
    private AgentSkillPolicyService service;

    private Skill skill(String... connectorCodes) {
        return Skill.builder()
                .id(SKILL_ID)
                .name("test-skill")
                .mdContent("body")
                .connectorCodes(List.of(connectorCodes))
                .userId(USER_ID)
                .build();
    }

    private void agentHasSkill(Skill skill) {
        when(agentSkillRepository.findByAgentId(AGENT_ID))
                .thenReturn(List.of(AgentSkill.builder().skillId(SKILL_ID).build()));
        when(skillRepository.findByIdInNotDeleted(any())).thenReturn(List.of(skill));
    }

    private void agentHasNoSkills() {
        when(agentSkillRepository.findByAgentId(AGENT_ID)).thenReturn(List.of());
    }

    private AgentConnection boundTo(String connectorCode) {
        AgentConnection binding = AgentConnection.builder().connectionId(CONNECTION_ID).build();
        when(agentConnectionRepository.findActiveByAgentId(AGENT_ID)).thenReturn(List.of(binding));
        when(connectionRepository.findByIdInNotDeleted(List.of(CONNECTION_ID)))
                .thenReturn(List.of(Connection.builder().id(CONNECTION_ID).connectorCode(connectorCode).build()));
        return binding;
    }

    private void connectorIsInternal(String code) {
        when(connectorRegistry.findHandler(code))
                .thenReturn(Optional.of(mock(InternalConnectorHandler.class)));
    }

    @Test
    @DisplayName("неизвестный connector_code (NotFound от bind) не роняет привязку скилла")
    void unknownConnectorCodeIsSwallowed() {
        agentHasSkill(skill("board", "bogus"));
        when(agentConnectionRepository.findActiveByAgentId(AGENT_ID)).thenReturn(List.of());
        when(connectionBindingService.bindInternal(USER_ID, AGENT_ID, "board")).thenReturn(null);
        when(connectionBindingService.bindInternal(USER_ID, AGENT_ID, "bogus"))
                .thenThrow(new NotFoundStatusException("Connector not found: bogus"));

        assertDoesNotThrow(() -> service.applyDiff(AGENT_ID, USER_ID));

        verify(connectionBindingService).bindInternal(USER_ID, AGENT_ID, "board");
        verify(connectionBindingService).bindInternal(USER_ID, AGENT_ID, "bogus");
    }

    @Test
    @DisplayName("внешний коннектор в скилле (BadRequest от bind) не роняет привязку скилла")
    void externalConnectorIsSwallowed() {
        agentHasSkill(skill("telegram"));
        when(agentConnectionRepository.findActiveByAgentId(AGENT_ID)).thenReturn(List.of());
        when(connectionBindingService.bindInternal(USER_ID, AGENT_ID, "telegram"))
                .thenThrow(new BadRequestStatusException(
                        "Connector telegram is external — bind an explicit connection instance"));

        assertDoesNotThrow(() -> service.applyDiff(AGENT_ID, USER_ID));
    }

    @Test
    @DisplayName("уже привязанный коннектор не биндится повторно и не снимается, пока его требует скилл")
    void alreadyBoundConnectorIsSkipped() {
        agentHasSkill(skill("board"));
        boundTo("board");

        service.applyDiff(AGENT_ID, USER_ID);

        verify(connectionBindingService, never()).bindInternal(any(), any(), any());
        verify(connectionBindingService, never()).removeBinding(any());
    }

    @Test
    @DisplayName("внутренняя привязка без скилла и без канала снимается")
    void staleInternalBindingIsRevoked() {
        agentHasNoSkills();
        AgentConnection binding = boundTo("board");
        connectorIsInternal("board");
        when(channelRepository.findByAgentIdAndConnectorCodeAndConnectionIdAndDeletedAtIsNull(
                AGENT_ID, "board", CONNECTION_ID)).thenReturn(Optional.empty());

        service.applyDiff(AGENT_ID, USER_ID);

        verify(connectionBindingService).removeBinding(binding);
    }

    @Test
    @DisplayName("привязку, удерживаемую активным каналом (webchat), реконсиляция не трогает")
    void channelHeldBindingSurvives() {
        agentHasNoSkills();
        boundTo("webchat");
        connectorIsInternal("webchat");
        when(channelRepository.findByAgentIdAndConnectorCodeAndConnectionIdAndDeletedAtIsNull(
                AGENT_ID, "webchat", CONNECTION_ID)).thenReturn(Optional.of(mock(Channel.class)));

        service.applyDiff(AGENT_ID, USER_ID);

        verify(connectionBindingService, never()).removeBinding(any());
    }

    @Test
    @DisplayName("привязку внешнего экземпляра реконсиляция не трогает")
    void externalBindingSurvives() {
        agentHasNoSkills();
        boundTo("telegram");
        when(connectorRegistry.findHandler("telegram")).thenReturn(Optional.empty());

        service.applyDiff(AGENT_ID, USER_ID);

        verify(connectionBindingService, never()).removeBinding(any());
    }
}
