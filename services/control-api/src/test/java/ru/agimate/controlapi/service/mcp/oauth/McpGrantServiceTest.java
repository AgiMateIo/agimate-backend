package ru.agimate.controlapi.service.mcp.oauth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.entities.McpOAuthGrant;
import ru.agimate.controlapi.database.enums.AgentType;
import ru.agimate.controlapi.database.enums.McpOAuthClientKind;
import ru.agimate.controlapi.database.enums.McpOAuthGrantStatus;
import ru.agimate.controlapi.database.repositories.AgentRepository;
import ru.agimate.controlapi.database.repositories.McpOAuthGrantRepository;
import ru.agimate.controlapi.database.repositories.McpOAuthTokenRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@DisplayName("McpGrantService: подключённые клиенты агента")
class McpGrantServiceTest {

    private final AgentRepository agentRepository = mock(AgentRepository.class);
    private final McpOAuthGrantRepository grantRepository = mock(McpOAuthGrantRepository.class);
    private final McpOAuthTokenRepository tokenRepository = mock(McpOAuthTokenRepository.class);
    private final McpGrantService service = new McpGrantService(agentRepository, grantRepository, tokenRepository);

    private final UUID owner = UUID.randomUUID();
    private Agent agent;
    private McpOAuthGrant grant;

    @BeforeEach
    void setUp() {
        agent = Agent.builder().id(UUID.randomUUID()).userId(owner).name("ops").type(AgentType.MCP).build();
        grant = McpOAuthGrant.builder().id(UUID.randomUUID()).userId(owner).agentId(agent.getId())
                .status(McpOAuthGrantStatus.ACTIVE).clientKind(McpOAuthClientKind.CIMD)
                .clientId("https://claude.ai/oauth/client.json").clientName("Claude").build();
        when(agentRepository.findById(agent.getId())).thenReturn(Optional.of(agent));
        when(grantRepository.findById(grant.getId())).thenReturn(Optional.of(grant));
        when(grantRepository.findByAgentIdAndStatusOrderByCreatedAtDesc(agent.getId(), McpOAuthGrantStatus.ACTIVE))
                .thenReturn(List.of(grant));
    }

    @Test
    @DisplayName("листинг — только активные гранты своего агента")
    void listsActiveGrants() {
        List<McpGrantView> views = service.list(owner, agent.getId());

        assertEquals(1, views.size());
        assertEquals(grant.getId(), views.getFirst().id());
        assertEquals("Claude", views.getFirst().clientName());
    }

    @Test
    @DisplayName("чужой агент — 404 и в листинге, и в отзыве; ничего не отзывается")
    void foreignAgentIsNotFound() {
        UUID stranger = UUID.randomUUID();

        assertThrows(NotFoundStatusException.class, () -> service.list(stranger, agent.getId()));
        assertThrows(NotFoundStatusException.class, () -> service.revoke(stranger, agent.getId(), grant.getId()));
        verify(grantRepository, never()).revoke(any(), any());
    }

    @Test
    @DisplayName("грант другого агента того же владельца — 404: путь называет агента, и грант обязан быть его")
    void grantOfAnotherAgentIsNotFound() {
        Agent sibling = Agent.builder().id(UUID.randomUUID()).userId(owner).name("other").type(AgentType.MCP).build();
        when(agentRepository.findById(sibling.getId())).thenReturn(Optional.of(sibling));

        assertThrows(NotFoundStatusException.class, () -> service.revoke(owner, sibling.getId(), grant.getId()));
        verify(grantRepository, never()).revoke(any(), any());
    }

    @Test
    @DisplayName("отзыв — грант отозван, его токены удалены")
    void revokes() {
        service.revoke(owner, agent.getId(), grant.getId());

        verify(grantRepository).revoke(eq(grant.getId()), any());
        verify(tokenRepository).deleteByGrantId(grant.getId());
    }

    @Test
    @DisplayName("удаление агента: гранты раньше токенов — в том же порядке, что обмен и refresh, иначе взаимная блокировка")
    void agentDeletionLocksGrantsFirst() {
        service.revokeAllForAgent(agent.getId());

        InOrder order = inOrder(grantRepository, tokenRepository);
        order.verify(grantRepository).revokeByAgentId(eq(agent.getId()), any());
        order.verify(tokenRepository).deleteByAgentId(agent.getId());
    }
}
