package ru.agimate.controlapi.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.lang.NonNull;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import ru.agimate.controlapi.database.enums.AgentType;
import ru.agimate.controlapi.service.AgentKeyAuthService;

import java.io.IOException;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class AgentAuthFilter extends OncePerRequestFilter {

    private static final String API_KEY_HEADER = "X-Api-Key";
    private static final String BEARER_PREFIX = "Bearer ";

    /** REST surface of the agent's own brain: settings, context, LLM credentials, tool calls. */
    public static final String ROLE_AGENT = "AGENT";

    /** Surface of a user-side client talking to the agent (the ACP WebSocket of an IDE). */
    public static final String ROLE_AGENT_CLIENT = "AGENT_CLIENT";

    /** MCP surface: the brain of an {@link AgentType#MCP} agent pulling its tools. */
    public static final String ROLE_MCP_AGENT = "MCP_AGENT";

    private final AgentKeyAuthService agentKeyAuthService;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {

        String apiKey = extractKey(request);

        if (StringUtils.hasText(apiKey)) {
            agentKeyAuthService.validateKey(apiKey)
                    .ifPresent(agent -> {
                        var principal = new AgentPrincipal(
                                agent.getName(),
                                agent.getId(),
                                agent.getUserId()
                        );

                        SecurityContextHolder.getContext().setAuthentication(
                                new AgentAuthToken(principal, authorities(agent.getType()))
                        );

                        log.debug("Agent key authenticated for agent: {} (user: {}, type: {})",
                                agent.getName(), agent.getUserId(), agent.getType());
                    });
        }

        filterChain.doFilter(request, response);
    }

    /**
     * The same key under two headers: {@code X-Api-Key} is what agents have always sent, and MCP
     * requires {@code Authorization: Bearer} — a client we do not write cannot be asked to use ours.
     */
    private static String extractKey(HttpServletRequest request) {
        String apiKey = request.getHeader(API_KEY_HEADER);
        if (StringUtils.hasText(apiKey)) {
            return apiKey;
        }
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        return authorization != null && authorization.startsWith(BEARER_PREFIX)
                ? authorization.substring(BEARER_PREFIX.length())
                : null;
    }

    /**
     * Surfaces the key opens, decided by the agent's type. The type says where the agent's brain
     * lives, and a brain has exactly one way in: a {@link AgentType#GENERIC} agent is driven by our
     * own worker over gRPC, so its key must not open the REST brain surface — {@code /agent/llm}
     * hands out decrypted LLM credentials, and no legitimate client of a GENERIC agent ever asks for
     * them over HTTP. The ACP WebSocket is the opposite direction (an IDE talking <em>to</em> the
     * agent), so every type whose brain can be pushed to keeps it — and {@link AgentType#MCP}, whose
     * brain only pulls, gets neither.
     */
    private static List<GrantedAuthority> authorities(AgentType type) {
        return switch (type) {
            case CENTRIFUGO, WEBHOOK -> List.of(role(ROLE_AGENT), role(ROLE_AGENT_CLIENT));
            case GENERIC -> List.of(role(ROLE_AGENT_CLIENT));
            case MCP -> List.of(role(ROLE_MCP_AGENT));
        };
    }

    private static GrantedAuthority role(String role) {
        return new SimpleGrantedAuthority("ROLE_" + role);
    }
}
