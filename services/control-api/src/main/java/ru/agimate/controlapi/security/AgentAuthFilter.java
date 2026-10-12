package ru.agimate.controlapi.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import ru.agimate.controlapi.database.enums.AgentType;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.service.AgentKeyAuthService;
import ru.agimate.controlapi.service.mcp.oauth.McpOAuthTokens;
import ru.agimate.controlapi.service.mcp.oauth.McpTokenService;

import java.io.IOException;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class AgentAuthFilter extends OncePerRequestFilter {

    private static final String API_KEY_HEADER = "X-Api-Key";
    private static final String BEARER_PREFIX = "Bearer ";

    /** REST surface of the agent's own brain: settings, context, skills, tool calls. */
    public static final String ROLE_AGENT = "AGENT";

    /** Surface of a user-side client talking to the agent (the ACP WebSocket of an IDE). */
    public static final String ROLE_AGENT_CLIENT = "AGENT_CLIENT";

    /** MCP surface: a brain pulling its tools itself. */
    public static final String ROLE_MCP_AGENT = "MCP_AGENT";

    /**
     * Set when a credential was presented and refused, so the {@code /mcp} entry point can add
     * {@code error="invalid_token"}: that is what sends an OAuth client to refresh or re-authorise.
     */
    public static final String INVALID_CREDENTIAL_ATTRIBUTE = AgentAuthFilter.class.getName() + ".invalidCredential";

    private final AgentKeyAuthService agentKeyAuthService;
    private final McpTokenService mcpTokenService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String apiKey = extractKey(request);

        if (StringUtils.hasText(apiKey)) {
            boolean authenticated = McpOAuthTokens.isAccessToken(apiKey)
                    ? authenticateOAuth(apiKey)
                    : authenticateKey(apiKey);
            if (!authenticated) {
                request.setAttribute(INVALID_CREDENTIAL_ATTRIBUTE, Boolean.TRUE);
            }
        }

        filterChain.doFilter(request, response);
    }

    private boolean authenticateKey(String apiKey) {
        return agentKeyAuthService.validateKey(apiKey)
                .map(agent -> {
                    authenticate(agent, authorities(agent.getType()));
                    log.debug("Agent key authenticated for agent: {} (user: {}, type: {})",
                            agent.getName(), agent.getUserId(), agent.getType());
                    return true;
                })
                .orElse(false);
    }

    /**
     * An OAuth access token was issued for {@code /mcp} — its audience — and opens nothing else, even
     * though the agent's key does. The type is the agent's current one: switched to {@code GENERIC}
     * after consent, the agent answers 403 rather than going on quietly.
     */
    private boolean authenticateOAuth(String accessToken) {
        return mcpTokenService.authenticate(accessToken)
                .map(access -> {
                    Agent agent = access.agent();
                    authenticate(agent, agent.getType().hasExternalBrain() ? List.of(role(ROLE_MCP_AGENT)) : List.of());
                    log.debug("MCP OAuth token authenticated for agent: {} (grant: {})", agent.getId(), access.grantId());
                    return true;
                })
                .orElse(false);
    }

    private static void authenticate(Agent agent, List<GrantedAuthority> authorities) {
        var principal = new AgentPrincipal(agent.getName(), agent.getId(), agent.getUserId());
        SecurityContextHolder.getContext().setAuthentication(new AgentAuthToken(principal, authorities));
    }

    /**
     * The same key under two headers: {@code X-Api-Key} is what agents have always sent, and MCP
     * requires {@code Authorization: Bearer} — a client we do not write cannot be asked to use ours.
     */
    private static String extractKey(HttpServletRequest request) {
        String bearer = bearerToken(request);
        // An OAuth token wins over X-Api-Key: a client with a stale key in its static headers that went
        // through OAuth keeps sending both, and the stale key would loop it through re-authorisation.
        if (bearer != null && McpOAuthTokens.isAccessToken(bearer)) {
            return bearer;
        }
        String apiKey = request.getHeader(API_KEY_HEADER);
        if (StringUtils.hasText(apiKey)) {
            return apiKey;
        }
        return bearer;
    }

    private static String bearerToken(HttpServletRequest request) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        // The scheme is case-insensitive (RFC 7235) — a client sending «bearer» is not a client sending
        // a wrong key, and answering 401 to it would send its author looking in the wrong place.
        return authorization != null
                && authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())
                ? authorization.substring(BEARER_PREFIX.length()).trim()
                : null;
    }

    /**
     * Surfaces the key opens, decided by the agent's type — and the type says one thing only: how
     * events reach the brain. Every agent whose brain is outside gets the same surfaces, whether we
     * push to it over a websocket, over a callback, or not at all; which of them a given client
     * speaks is its own business. {@link AgentType#GENERIC} is the exception, and not by degree: its
     * brain is our own worker on gRPC, so the whole HTTP brain surface has no legitimate caller and
     * stays shut. What remains for it is {@code /acp} — the opposite direction, an IDE talking
     * <em>to</em> the agent.
     */
    private static List<GrantedAuthority> authorities(AgentType type) {
        return switch (type) {
            case CENTRIFUGO, WEBHOOK, MCP ->
                    List.of(role(ROLE_AGENT), role(ROLE_AGENT_CLIENT), role(ROLE_MCP_AGENT));
            case GENERIC -> List.of(role(ROLE_AGENT_CLIENT));
        };
    }

    private static GrantedAuthority role(String role) {
        return new SimpleGrantedAuthority("ROLE_" + role);
    }
}
