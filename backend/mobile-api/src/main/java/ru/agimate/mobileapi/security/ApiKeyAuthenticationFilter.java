package ru.agimate.mobileapi.security;

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
import ru.agimate.mobileapi.config.ApiKeyProperties;

import java.io.IOException;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final String API_KEY_HEADER = "X-Api-Key";

    private final ApiKeyProperties apiKeyProperties;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {

        String apiKey = request.getHeader(API_KEY_HEADER);

        if (StringUtils.hasText(apiKey) && apiKeyProperties.getApiKeys() != null) {
            apiKeyProperties.getApiKeys().stream()
                    .filter(entry -> entry.getKey().equals(apiKey))
                    .findFirst()
                    .ifPresent(entry -> {
                        var authorities = List.of(new SimpleGrantedAuthority("ROLE_DEVICE"));
                        var principal = new ApiKeyPrincipal(entry.getName(), apiKey);

                        SecurityContextHolder.getContext().setAuthentication(
                                new ApiKeyAuthenticationToken(principal, authorities)
                        );

                        log.debug("API key authenticated for: {}", entry.getName());
                    });
        }

        filterChain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Only filter /connection/** endpoints
        String servletPath = request.getServletPath();
        return !servletPath.startsWith("/connection");
    }
}
