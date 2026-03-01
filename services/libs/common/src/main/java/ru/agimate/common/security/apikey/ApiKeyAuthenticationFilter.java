package ru.agimate.common.security.apikey;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import ru.agimate.common.rest.ErrorResponse;
import ru.agimate.common.security.UserRole;
import ru.agimate.common.util.JsonUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * API key authentication filter.
 * Processes API keys from X-Api-Key header.
 * Validates keys via gRPC introspect call to user-api (with Caffeine cache).
 */
@RequiredArgsConstructor
@Slf4j
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final String API_KEY_HEADER = "X-Api-Key";

    private final ApiKeyIntrospectService apiKeyIntrospectService;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {

        String apiKey = request.getHeader(API_KEY_HEADER);

        if (StringUtils.hasText(apiKey)) {
            var resultOpt = apiKeyIntrospectService.introspect(apiKey);

            if (resultOpt.isPresent()) {
                var result = resultOpt.get();

                if (UserRole.GUEST.name().equals(result.userRole())) {
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    response.setContentType("application/json");
                    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                    response.getWriter().write(JsonUtils.writeValueAsString(
                            new ErrorResponse("Access denied. Insufficient permissions.")));
                    response.getWriter().flush();
                    return;
                }

                var authorities = List.of(new SimpleGrantedAuthority("ROLE_CONNECTOR"));
                var principal = new ApiKeyPrincipal(
                        result.keyPubId(),
                        result.userPubId()
                );

                SecurityContextHolder.getContext().setAuthentication(
                        new ApiKeyAuthenticationToken(principal, authorities)
                );

                log.debug("API key authenticated via introspect (user: {})", result.userPubId());
            }
        }

        filterChain.doFilter(request, response);
    }
}
