package ru.agimate.controlapi.abac;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.agimate.controlapi.database.entities.AgentConnection;
import ru.agimate.controlapi.database.entities.AgentConnectionPolicy;
import ru.agimate.controlapi.database.entities.Connection;

import ru.agimate.controlapi.database.enums.PolicyKind;
import ru.agimate.controlapi.database.repositories.AgentConnectionPolicyRepository;
import ru.agimate.controlapi.database.repositories.AgentConnectionRepository;
import ru.agimate.controlapi.database.repositories.ConnectionRepository;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("AgentConnectionPolicyService — PATCH-семантика update")
class AgentConnectionPolicyServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final UUID BINDING_ID = UUID.randomUUID();

    private final AgentConnectionPolicyRepository policyRepository = mock(AgentConnectionPolicyRepository.class);
    private final AgentConnectionRepository agentConnectionRepository = mock(AgentConnectionRepository.class);
    private final ConnectionRepository connectionRepository = mock(ConnectionRepository.class);
    private final ConnectionAccessEvaluator accessEvaluator = mock(ConnectionAccessEvaluator.class);

    private final AgentConnectionPolicyService service = new AgentConnectionPolicyService(
            policyRepository, agentConnectionRepository, connectionRepository, accessEvaluator);

    private final AgentConnectionPolicy policy = AgentConnectionPolicy.builder()
            .id(UUID.randomUUID())
            .agentConnectionId(BINDING_ID)
            .kind(PolicyKind.TOOL)
            .name("read")
            .effect(AccessEffect.ALLOW)
            .paramsFilter(Map.of("k", "v"))
            .description("old")
            .build();

    @BeforeEach
    void setUp() {
        when(policyRepository.findById(policy.getId())).thenReturn(Optional.of(policy));
        when(policyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        UUID connectionId = UUID.randomUUID();
        when(agentConnectionRepository.findById(BINDING_ID)).thenReturn(Optional.of(
                AgentConnection.builder().id(BINDING_ID).agentId(AGENT_ID).connectionId(connectionId)
                        .build()));
        when(connectionRepository.findByIdNotDeleted(connectionId)).thenReturn(Optional.of(
                Connection.builder().id(connectionId).connectorCode("telegram").userId(USER_ID).build()));
    }

    @Test
    @DisplayName("пустая строка description очищает поле (канон /manage: '' = clear)")
    void blankDescriptionClears() {
        AgentConnectionPolicy updated = service.update(USER_ID, BINDING_ID, policy.getId(),
                AccessEffect.ALLOW, Map.of("k", "v"), "");

        assertNull(updated.getDescription());
    }

    @Test
    @DisplayName("null description — поле не трогается")
    void nullDescriptionKeeps() {
        AgentConnectionPolicy updated = service.update(USER_ID, BINDING_ID, policy.getId(),
                AccessEffect.ALLOW, Map.of("k", "v"), null);

        assertEquals("old", updated.getDescription());
    }

    @Test
    @DisplayName("непустой description записывается")
    void nonBlankDescriptionSets() {
        AgentConnectionPolicy updated = service.update(USER_ID, BINDING_ID, policy.getId(),
                AccessEffect.ALLOW, Map.of("k", "v"), "new desc");

        assertEquals("new desc", updated.getDescription());
        verify(policyRepository).save(policy);
    }
}
