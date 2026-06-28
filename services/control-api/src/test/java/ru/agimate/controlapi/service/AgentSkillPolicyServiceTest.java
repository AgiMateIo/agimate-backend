package ru.agimate.controlapi.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.controlapi.database.entities.AgentConnection;
import ru.agimate.controlapi.database.entities.AgentSkill;
import ru.agimate.controlapi.database.entities.Connection;
import ru.agimate.controlapi.database.entities.Skill;
import ru.agimate.controlapi.database.repositories.AgentConnectionRepository;
import ru.agimate.controlapi.database.repositories.AgentSkillRepository;
import ru.agimate.controlapi.database.repositories.ConnectionRepository;
import ru.agimate.controlapi.database.repositories.SkillRepository;
import ru.agimate.controlapi.service.connection.ConnectionBindingService;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AgentSkillPolicyService.applyDiff")
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
    private ConnectionBindingService connectionBindingService;

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

    @Test
    @DisplayName("неизвестный connector_code (NotFound от bind) не роняет привязку скилла")
    void unknownConnectorCodeIsSwallowed() {
        agentHasSkill(skill("board", "bogus"));
        when(agentConnectionRepository.findActiveByAgentId(AGENT_ID)).thenReturn(List.of());
        when(connectionBindingService.bind(eq(USER_ID), eq(AGENT_ID), eq("board"), isNull(), isNull()))
                .thenReturn(null);
        when(connectionBindingService.bind(eq(USER_ID), eq(AGENT_ID), eq("bogus"), isNull(), isNull()))
                .thenThrow(new NotFoundStatusException("Connector not found: bogus"));

        assertDoesNotThrow(() -> service.applyDiff(AGENT_ID, USER_ID));

        verify(connectionBindingService).bind(eq(USER_ID), eq(AGENT_ID), eq("board"), isNull(), isNull());
        verify(connectionBindingService).bind(eq(USER_ID), eq(AGENT_ID), eq("bogus"), isNull(), isNull());
    }

    @Test
    @DisplayName("INSTANCE-коннектор (BadRequest от bind) не роняет привязку скилла")
    void instanceConnectorIsSwallowed() {
        agentHasSkill(skill("telegram"));
        when(agentConnectionRepository.findActiveByAgentId(AGENT_ID)).thenReturn(List.of());
        when(connectionBindingService.bind(eq(USER_ID), eq(AGENT_ID), eq("telegram"), isNull(), isNull()))
                .thenThrow(new BadRequestStatusException("INSTANCE connector requires an explicit connectionId"));

        assertDoesNotThrow(() -> service.applyDiff(AGENT_ID, USER_ID));
    }

    @Test
    @DisplayName("уже привязанный коннектор не биндится повторно")
    void alreadyBoundConnectorIsSkipped() {
        agentHasSkill(skill("board"));
        when(agentConnectionRepository.findActiveByAgentId(AGENT_ID))
                .thenReturn(List.of(AgentConnection.builder().connectionId(CONNECTION_ID).build()));
        when(connectionRepository.findByIdInNotDeleted(List.of(CONNECTION_ID)))
                .thenReturn(List.of(Connection.builder().id(CONNECTION_ID).connectorCode("board").build()));

        service.applyDiff(AGENT_ID, USER_ID);

        verify(connectionBindingService, never()).bind(any(), any(), any(), any(), any());
    }
}
