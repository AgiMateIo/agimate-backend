package ru.agimate.userapi.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/** Authenticates our other services on {@code /internal/**}. Carries no identity beyond «it is us». */
@Component
@RequiredArgsConstructor
@Slf4j
public class InternalAuthFilter extends OncePerRequestFilter {

    public static final String ROLE_INTERNAL = "INTERNAL";

    private static final String INTERNAL_AUTH_KEY_HEADER = "X-Internal-Auth-Key";

    private final InternalKeyAuthService internalKeyAuthService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String key = request.getHeader(INTERNAL_AUTH_KEY_HEADER);

        if (StringUtils.hasText(key) && internalKeyAuthService.isValid(key)) {
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(
                            "internal", null, List.of(new SimpleGrantedAuthority("ROLE_" + ROLE_INTERNAL))));
            log.debug("Internal key authenticated for {}", request.getRequestURI());
        }

        filterChain.doFilter(request, response);
    }
}
