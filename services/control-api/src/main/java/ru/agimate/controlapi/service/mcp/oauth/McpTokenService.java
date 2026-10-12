package ru.agimate.controlapi.service.mcp.oauth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.security.keys.AppKeyUtils;
import ru.agimate.common.security.keys.GeneratedAppKey;
import ru.agimate.common.security.keys.ParsedAppKey;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.entities.McpOAuthGrant;
import ru.agimate.controlapi.database.entities.McpOAuthToken;
import ru.agimate.controlapi.database.enums.McpOAuthGrantStatus;
import ru.agimate.controlapi.database.enums.McpOAuthTokenKind;
import ru.agimate.controlapi.database.repositories.AgentRepository;
import ru.agimate.controlapi.database.repositories.McpOAuthGrantRepository;
import ru.agimate.controlapi.database.repositories.McpOAuthTokenRepository;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Codes and tokens of the MCP server's authorization server (docs/decisions/mcp-server-oauth.md).
 *
 * <p>Every state change of a grant starts by locking its row ({@code findByIdForUpdate}), so an exchange,
 * a refresh and a revocation of one grant never interleave.
 *
 * <p>A refused exchange never throws out of a transactional method: a replayed code or refresh token
 * revokes the grant, and that revocation has to commit while the client is told {@code invalid_grant}
 * — an exception would mark the transaction rollback-only and take the revocation with it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class McpTokenService {

    private static final Duration LAST_USED_GRANULARITY = Duration.ofMinutes(1);

    private final McpOAuthGrantRepository grantRepository;
    private final McpOAuthTokenRepository tokenRepository;
    private final AgentRepository agentRepository;
    private final McpOAuthEndpoints endpoints;

    /** The code of a freshly consented grant; part of the consent's transaction. */
    @Transactional
    public String issueCode(UUID grantId, LocalDateTime now) {
        return issue(grantId, McpOAuthTokenKind.CODE, now);
    }

    @Transactional
    public TokenResult exchangeCode(String code, String codeVerifier, String clientId,
                                    String redirectUri, String resource) {
        if (isBlank(code) || isBlank(codeVerifier) || isBlank(clientId)) {
            return TokenResult.refused(OAuthErrors.INVALID_REQUEST, "code, code_verifier and client_id are required");
        }
        LocalDateTime now = LocalDateTime.now();
        Optional<McpOAuthToken> found = find(code, McpOAuthTokenKind.CODE);
        if (found.isEmpty()) {
            return TokenResult.refused(OAuthErrors.INVALID_GRANT, "Unknown authorization code");
        }
        McpOAuthToken token = found.get();
        McpOAuthGrant grant = grantRepository.findByIdForUpdate(token.getGrantId()).orElseThrow();

        // Claimed before any other check, so a failed attempt burns the code like a successful one.
        if (tokenRepository.supersede(token.getId(), now) == 0) {
            // A second presenter means the code was seen by two parties; whatever the first got is void.
            revokeGrant(grant.getId(), now);
            log.warn("MCP OAuth: authorization code replayed, grant {} revoked", grant.getId());
            return TokenResult.refused(OAuthErrors.INVALID_GRANT, "Authorization code was already used");
        }
        if (token.getExpiresAt().isBefore(now)) {
            return TokenResult.refused(OAuthErrors.INVALID_GRANT, "Authorization code expired");
        }
        if (grant.getStatus() != McpOAuthGrantStatus.PENDING) {
            return TokenResult.refused(OAuthErrors.INVALID_GRANT, "Authorization code is no longer valid");
        }
        if (!grant.getClientId().equals(clientId)) {
            return TokenResult.refused(OAuthErrors.INVALID_GRANT, "client_id does not match the authorization code");
        }
        // OAuth 2.1 dropped redirect_uri from the exchange (PKCE covers it), so it is compared only when sent.
        if (redirectUri != null && !redirectUri.equals(grant.getRedirectUri())) {
            return TokenResult.refused(OAuthErrors.INVALID_GRANT, "redirect_uri does not match the authorization request");
        }
        if (resource != null && !endpoints.isOwnResource(resource)) {
            return TokenResult.refused(OAuthErrors.INVALID_TARGET, "Unknown resource");
        }
        if (!PkceS256.matches(codeVerifier, grant.getCodeChallenge())) {
            return TokenResult.refused(OAuthErrors.INVALID_GRANT, "code_verifier does not match the code challenge");
        }
        if (usableAgent(grant.getAgentId()).isEmpty()) {
            return TokenResult.refused(OAuthErrors.INVALID_GRANT, "The agent is no longer available");
        }
        if (grantRepository.activate(grant.getId(), now) == 0) {
            return TokenResult.refused(OAuthErrors.INVALID_GRANT, "Authorization code is no longer valid");
        }
        log.info("MCP OAuth: grant {} activated for agent {} (client {})", grant.getId(), grant.getAgentId(), grant.getClientId());
        return issuePair(grant.getId(), now);
    }

    /**
     * Rotation with a retry window. A token rotated less than {@link McpOAuthTokens#RETRY_WINDOW} ago is
     * a client that lost the response — it gets a fresh pair once, and nothing else of the grant is
     * touched, so two parallel refreshes both end with a working chain. Only hashes are stored, so «the
     * current pair» cannot be handed out again. A second retry, or any use later than the window, is a
     * replay, and the grant goes.
     */
    @Transactional
    public TokenResult refresh(String refreshToken, String clientId) {
        if (isBlank(refreshToken)) {
            return TokenResult.refused(OAuthErrors.INVALID_REQUEST, "refresh_token is required");
        }
        LocalDateTime now = LocalDateTime.now();
        Optional<McpOAuthToken> found = find(refreshToken, McpOAuthTokenKind.REFRESH);
        if (found.isEmpty()) {
            return TokenResult.refused(OAuthErrors.INVALID_GRANT, "Unknown refresh token");
        }
        McpOAuthToken token = found.get();
        McpOAuthGrant grant = grantRepository.findByIdForUpdate(token.getGrantId()).orElseThrow();

        if (grant.getStatus() != McpOAuthGrantStatus.ACTIVE) {
            return TokenResult.refused(OAuthErrors.INVALID_GRANT, "The grant has been revoked");
        }
        if (clientId != null && !grant.getClientId().equals(clientId)) {
            return TokenResult.refused(OAuthErrors.INVALID_GRANT, "client_id does not match the refresh token");
        }
        if (token.getExpiresAt().isBefore(now)) {
            return TokenResult.refused(OAuthErrors.INVALID_GRANT, "Refresh token expired");
        }
        if (token.getSupersededAt() != null && token.getSupersededAt().plus(McpOAuthTokens.RETRY_WINDOW).isBefore(now)) {
            revokeGrant(grant.getId(), now);
            log.warn("MCP OAuth: rotated refresh token replayed, grant {} revoked", grant.getId());
            return TokenResult.refused(OAuthErrors.INVALID_GRANT, "Refresh token was already used");
        }
        // Not current any more — rotated earlier, or by a parallel refresh this update waited for. That is
        // the retry case, and a token gets exactly one: unlimited retries would let a stolen token mint
        // live chains for the whole window. The second presentation is a replay.
        if (tokenRepository.supersede(token.getId(), now) == 0) {
            LocalDateTime windowStart = now.minus(McpOAuthTokens.RETRY_WINDOW);
            if (tokenRepository.spendRetry(token.getId(), windowStart, windowStart.minusSeconds(1), now) == 0) {
                revokeGrant(grant.getId(), now);
                log.warn("MCP OAuth: rotated refresh token presented again after its retry, grant {} revoked", grant.getId());
                return TokenResult.refused(OAuthErrors.INVALID_GRANT, "Refresh token was already used");
            }
        }

        if (usableAgent(grant.getAgentId()).isEmpty()) {
            return TokenResult.refused(OAuthErrors.INVALID_GRANT, "The agent is no longer available");
        }
        return issuePair(grant.getId(), now);
    }

    /**
     * RFC 7009: any of the grant's tokens revokes the whole grant — a client disconnecting means it.
     * Unknown tokens are not an error; the endpoint answers the same either way.
     */
    @Transactional
    public void revoke(String presented, String clientId) {
        Optional<McpOAuthToken> found = McpOAuthTokens.kindOf(presented).flatMap(kind -> find(presented, kind));
        if (found.isEmpty()) {
            return;
        }
        McpOAuthGrant grant = grantRepository.findByIdForUpdate(found.get().getGrantId()).orElseThrow();
        if (clientId != null && !grant.getClientId().equals(clientId)) {
            return;
        }
        revokeGrant(grant.getId(), LocalDateTime.now());
        log.info("MCP OAuth: grant {} revoked by its client", grant.getId());
    }

    /**
     * An access token on {@code /mcp}. The agent is checked on every call, not at issue: it may have
     * been switched off or deleted since, and revocation has to be immediate.
     */
    @Transactional
    public Optional<McpAccess> authenticate(String accessToken) {
        LocalDateTime now = LocalDateTime.now();
        Optional<McpOAuthToken> found = find(accessToken, McpOAuthTokenKind.ACCESS)
                .filter(token -> !token.getExpiresAt().isBefore(now));
        if (found.isEmpty()) {
            return Optional.empty();
        }
        McpOAuthGrant grant = grantRepository.findById(found.get().getGrantId()).orElseThrow();
        if (grant.getStatus() != McpOAuthGrantStatus.ACTIVE) {
            return Optional.empty();
        }
        Optional<Agent> agent = agentRepository.findById(grant.getAgentId()).filter(Agent::isEnabled);
        if (agent.isEmpty()) {
            return Optional.empty();
        }
        LocalDateTime threshold = now.minus(LAST_USED_GRANULARITY);
        if (grant.getLastUsedAt() == null || grant.getLastUsedAt().isBefore(threshold)) {
            grantRepository.touch(grant.getId(), now, threshold);
        }
        return Optional.of(new McpAccess(agent.get(), grant.getId()));
    }

    private TokenResult issuePair(UUID grantId, LocalDateTime now) {
        String access = issue(grantId, McpOAuthTokenKind.ACCESS, now);
        String refresh = issue(grantId, McpOAuthTokenKind.REFRESH, now);
        return new TokenResult.Issued(access, refresh, McpOAuthTokens.ACCESS_TTL.toSeconds());
    }

    private String issue(UUID grantId, McpOAuthTokenKind kind, LocalDateTime now) {
        GeneratedAppKey key = AppKeyUtils.generate(McpOAuthTokens.prefixOf(kind));
        tokenRepository.save(McpOAuthToken.builder()
                .grantId(grantId)
                .kind(kind)
                .keyId(key.keyId())
                .keyHash(key.secretHash())
                .expiresAt(now.plus(McpOAuthTokens.ttlOf(kind)))
                .build());
        return key.fullKey();
    }

    private void revokeGrant(UUID grantId, LocalDateTime now) {
        grantRepository.revoke(grantId, now);
        tokenRepository.deleteByGrantId(grantId);
    }

    /** Found, of the claimed kind, and its secret matches; a malformed token is simply not found. */
    private Optional<McpOAuthToken> find(String presented, McpOAuthTokenKind kind) {
        if (presented == null || !presented.startsWith(McpOAuthTokens.prefixOf(kind))) {
            return Optional.empty();
        }
        ParsedAppKey parsed;
        try {
            parsed = AppKeyUtils.parse(presented);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        if (!AppKeyUtils.verifyChecksum(parsed)) {
            return Optional.empty();
        }
        return tokenRepository.findByKeyId(parsed.keyId())
                .filter(token -> token.getKind() == kind)
                .filter(token -> AppKeyUtils.verifySecret(parsed.secret(), token.getKeyHash()));
    }

    private Optional<Agent> usableAgent(UUID agentId) {
        return agentRepository.findById(agentId)
                .filter(Agent::isEnabled)
                .filter(agent -> agent.getType().hasExternalBrain());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
