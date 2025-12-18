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
import ru.agimate.userapi.security.CustomUserDetailsService;

import java.io.IOException;

@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtils jwtUtils;
    private final CustomUserDetailsService customUserDetailsService;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {

        // Extract JWT from request header
        String jwt = parseJwt(request);

        if (jwt != null) {
            jwtUtils.extractClaimsFromValidAccessToken(jwt)
                    .flatMap(w -> customUserDetailsService.findByPubId(w.claims().getSubject()))
                    .ifPresent(userDetails -> {
                        SecurityContextHolder.getContext().setAuthentication(
                                new JwtAuthenticationToken(
                                        userDetails,
                                        userDetails.getAuthorities()
                                )
                        );
                    });
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