package ru.agimate.userapi.security.jwt;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import ru.agimate.common.security.jwt.AgimateUserPrincipal;
import ru.agimate.common.security.jwt.JwtAuthenticationToken;
import ru.agimate.common.security.jwt.JwtService;
import ru.agimate.userapi.service.UserService;

import java.io.IOException;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class JwtDbAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserService userService;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {

        // Extract JWT from request header
        String jwt = parseJwt(request);

        if (jwt != null) {
            jwtService.extractClaimsFromValidAccessToken(jwt)
                    .flatMap(w -> userService.findByPubId(UUID.fromString(w.claims().getSubject())))
                    .ifPresent(userEntity -> SecurityContextHolder.getContext().setAuthentication(
                            new JwtAuthenticationToken(
                                    new AgimateUserPrincipal(userEntity.getPubId().toString())
                            )
                    ));
        }

        filterChain.doFilter(request, response);
    }

    private String parseJwt(HttpServletRequest request) {
        String headerAuth = request.getHeader("Authorization");

        if (StringUtils.hasText(headerAuth) && headerAuth.startsWith("Bearer ")) {
            return headerAuth.substring(7); // Remove "Bearer " prefix
        }

        return null;
    }


    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Don't filter the login endpoint and OAuth2 endpoints
        String servletPath = request.getServletPath();
        return servletPath.equals("/auth/login") ||
                servletPath.startsWith("/oauth2/") ||
                servletPath.equals("/error");
    }
}