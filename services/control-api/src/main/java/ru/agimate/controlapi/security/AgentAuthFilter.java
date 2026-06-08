package ru.agimate.controlapi.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import ru.agimate.controlapi.service.AgentKeyAuthService;

import java.io.IOException;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class AgentAuthFilter extends OncePerRequestFilter {

    private static final String API_KEY_HEADER = "X-Api-Key";

    private final AgentKeyAuthService agentKeyAuthService;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {

        String apiKey = request.getHeader(API_KEY_HEADER);

        if (StringUtils.hasText(apiKey)) {
            agentKeyAuthService.validateKey(apiKey)
                    .ifPresent(agent -> {
                        var authorities = List.of(new SimpleGrantedAuthority("ROLE_AGENT"));
                        var principal = new AgentPrincipal(
                                agent.getName(),
                                agent.getId(),
                                agent.getUserId()
                        );

                        SecurityContextHolder.getContext().setAuthentication(
                                new AgentAuthToken(principal, authorities)
                        );

                        log.debug("Agent key authenticated for agent: {} (user: {})",
                                agent.getName(), agent.getUserId());
                    });
        }

        filterChain.doFilter(request, response);
    }
}
