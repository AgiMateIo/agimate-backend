package ru.agimate.controlapi.service.mcp.oauth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.controlapi.database.entities.McpOAuthGrant;
import ru.agimate.controlapi.database.enums.McpOAuthGrantStatus;
import ru.agimate.controlapi.database.repositories.AgentRepository;
import ru.agimate.controlapi.database.repositories.McpOAuthGrantRepository;
import ru.agimate.controlapi.database.repositories.McpOAuthTokenRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** The clients connected to an agent over OAuth, for the agent's owner. */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class McpGrantService {

    private final AgentRepository agentRepository;
    private final McpOAuthGrantRepository grantRepository;
    private final McpOAuthTokenRepository tokenRepository;

    public List<McpGrantView> list(UUID userId, UUID agentId) {
        requireOwnedAgent(userId, agentId);
        return grantRepository.findByAgentIdAndStatusOrderByCreatedAtDesc(agentId, McpOAuthGrantStatus.ACTIVE).stream()
                .map(McpGrantService::view)
                .toList();
    }

    @Transactional
    public void revoke(UUID userId, UUID agentId, UUID grantId) {
        requireOwnedAgent(userId, agentId);
        McpOAuthGrant grant = grantRepository.findById(grantId)
                .filter(found -> found.getAgentId().equals(agentId))
                .orElseThrow(() -> new NotFoundStatusException("Client not found"));
        grantRepository.revoke(grant.getId(), LocalDateTime.now());
        tokenRepository.deleteByGrantId(grant.getId());
        log.info("MCP OAuth: grant {} revoked by the owner of agent {}", grant.getId(), agentId);
    }

    /**
     * Part of the agent's deletion. The agent is soft-deleted and hidden by its restriction, so its
     * tokens would stop working anyway; revoking keeps the listings and the token table honest.
     * Grants before tokens — the order every other path locks them in, or a refresh running alongside
     * deadlocks with the deletion.
     */
    @Transactional
    public void revokeAllForAgent(UUID agentId) {
        grantRepository.revokeByAgentId(agentId, LocalDateTime.now());
        tokenRepository.deleteByAgentId(agentId);
    }

    private void requireOwnedAgent(UUID userId, UUID agentId) {
        agentRepository.findById(agentId)
                .filter(agent -> agent.getUserId().equals(userId))
                .orElseThrow(() -> new NotFoundStatusException("Agent not found"));
    }

    private static McpGrantView view(McpOAuthGrant grant) {
        return new McpGrantView(grant.getId(), grant.getClientId(), grant.getClientName(), grant.getClientKind(),
                grant.getCreatedAt(), grant.getLastUsedAt());
    }
}
