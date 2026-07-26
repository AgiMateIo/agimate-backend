package ru.agimate.controlapi.service.connection;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.controlapi.abac.ConnectionAccessEvaluator;
import ru.agimate.controlapi.connectors.core.ConnectorRegistry;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.entities.AgentConnection;
import ru.agimate.controlapi.database.entities.Connection;
import ru.agimate.controlapi.database.repositories.AgentConnectionPolicyRepository;
import ru.agimate.controlapi.database.repositories.AgentConnectionRepository;
import ru.agimate.controlapi.database.repositories.AgentRepository;
import ru.agimate.controlapi.database.repositories.ConnectionRepository;
import ru.agimate.controlapi.service.seed.ConnectorTexts;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ConnectionBindingService.listForConnection (кто использует экземпляр)")
class ConnectionBindingServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID CONNECTION_ID = UUID.randomUUID();

    @Mock
    private ConnectionRepository connectionRepository;
    @Mock
    private AgentConnectionRepository agentConnectionRepository;
    @Mock
    private AgentConnectionPolicyRepository policyRepository;
    @Mock
    private AgentRepository agentRepository;
    @Mock
    private ConnectionAccessEvaluator accessEvaluator;
    @Mock
    private org.springframework.context.ApplicationEventPublisher eventPublisher;
    @Mock
    private ConnectorRegistry connectorRegistry;
    @Mock
    private ConnectorTexts connectorTexts;

    @InjectMocks
    private ConnectionBindingService service;

    private static Agent agent(UUID id, String name, boolean enabled) {
        Agent agent = new Agent();
        agent.setId(id);
        agent.setUserId(USER_ID);
        agent.setName(name);
        agent.setEnabled(enabled);
        return agent;
    }

    private static AgentConnection binding(UUID agentId) {
        return AgentConnection.builder()
                .id(UUID.randomUUID())
                .agentId(agentId)
                .connectionId(CONNECTION_ID)
                .build();
    }

    private void connectionOwned() {
        Connection connection = new Connection();
        connection.setId(CONNECTION_ID);
        connection.setUserId(USER_ID);
        when(connectionRepository.findByIdAndUserIdNotDeleted(CONNECTION_ID, USER_ID))
                .thenReturn(Optional.of(connection));
    }

    @Nested
    @DisplayName("Владение экземпляром")
    class Ownership {

        @Test
        @DisplayName("Чужой/несуществующий connection — 404, привязки не читаем")
        void notFoundForForeignConnection() {
            when(connectionRepository.findByIdAndUserIdNotDeleted(CONNECTION_ID, USER_ID))
                    .thenReturn(Optional.empty());

            assertThrows(NotFoundStatusException.class, () -> service.listForConnection(USER_ID, CONNECTION_ID));
            verify(agentConnectionRepository, never()).findActiveByConnectionId(CONNECTION_ID);
        }
    }

    @Nested
    @DisplayName("Состав выдачи")
    class Content {

        @Test
        @DisplayName("Отключённый агент остаётся в выдаче — это инвентарь использования")
        void includesDisabledAgents() {
            connectionOwned();
            UUID enabledId = UUID.randomUUID();
            UUID disabledId = UUID.randomUUID();
            when(agentConnectionRepository.findActiveByConnectionId(CONNECTION_ID))
                    .thenReturn(List.of(binding(enabledId), binding(disabledId)));
            when(agentRepository.findAllById(anyList()))
                    .thenReturn(List.of(agent(enabledId, "on", true), agent(disabledId, "off", false)));

            var views = service.listForConnection(USER_ID, CONNECTION_ID);

            assertEquals(2, views.size());
            assertEquals(List.of(enabledId, disabledId), views.stream().map(v -> v.agent().getId()).toList());
        }

        @Test
        @DisplayName("Мягко удалённый агент выпадает: binding жив, агента репозиторий не отдаёт")
        void skipsSoftDeletedAgent() {
            connectionOwned();
            UUID aliveId = UUID.randomUUID();
            UUID deletedId = UUID.randomUUID();
            when(agentConnectionRepository.findActiveByConnectionId(CONNECTION_ID))
                    .thenReturn(List.of(binding(aliveId), binding(deletedId)));
            when(agentRepository.findAllById(anyList())).thenReturn(List.of(agent(aliveId, "alive", true)));

            var views = service.listForConnection(USER_ID, CONNECTION_ID);

            assertEquals(1, views.size());
            assertEquals(aliveId, views.getFirst().agent().getId());
        }

        @Test
        @DisplayName("Нет привязок — пустой список без обращения к агентам")
        void emptyWithoutBindings() {
            connectionOwned();
            when(agentConnectionRepository.findActiveByConnectionId(CONNECTION_ID)).thenReturn(List.of());

            assertTrue(service.listForConnection(USER_ID, CONNECTION_ID).isEmpty());
            verify(agentRepository, never()).findAllById(anyList());
        }
    }
}
