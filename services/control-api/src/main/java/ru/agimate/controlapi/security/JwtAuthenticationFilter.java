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
import ru.agimate.common.security.jwt.AgimateUserPrincipal;
import ru.agimate.common.security.jwt.JwtAuthenticationToken;
import ru.agimate.common.security.jwt.JwtService;

import java.io.IOException;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {

        String jwt = parseJwt(request);

        if (jwt != null) {
            jwtService.extractClaimsFromValidAccessToken(jwt)
                    .ifPresent(wrappedJwt -> {
                        // Extract roles from JWT claims
                        @SuppressWarnings("unchecked")
                        List<String> roles = wrappedJwt.claims().get("roles", List.class);

                        var authorities = roles != null
                                ? roles.stream().map(SimpleGrantedAuthority::new).toList()
                                : AgimateUserPrincipal.DEFAULT_ROLES;

                        // Create principal with subject (userId)
                        String subject = wrappedJwt.claims().getSubject();
                        var principal = new AgimateUserPrincipal(subject, authorities);

                        SecurityContextHolder.getContext().setAuthentication(
                                new JwtAuthenticationToken(principal)
                        );

                        log.debug("JWT authenticated for user: {}", subject);
                    });
        }

        filterChain.doFilter(request, response);
    }

    private String parseJwt(HttpServletRequest request) {
        String headerAuth = request.getHeader("Authorization");

        if (StringUtils.hasText(headerAuth) && headerAuth.startsWith("Bearer ")) {
            return headerAuth.substring(7);
        }

        return null;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String servletPath = request.getServletPath();
        return !servletPath.startsWith("/manage");
    }

}
