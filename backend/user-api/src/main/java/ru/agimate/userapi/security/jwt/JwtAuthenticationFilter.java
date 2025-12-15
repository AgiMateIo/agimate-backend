package ru.agimate.userapi.security.jwt;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtils jwtUtils;
    private final UserDetailsService userDetailsService;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {

        // Extract JWT from request header
        String jwt = parseJwt(request);

        if (jwt != null && jwtUtils.validateToken(jwt, getDummyUserDetails(jwt))) {
            String username = jwtUtils.extractUsername(jwt);

            // Load user details from database
            UserDetails userDetails = userDetailsService.loadUserByUsername(username);

            // Check if token is still valid with actual user details
            if (jwtUtils.validateToken(jwt, userDetails)) {
                JwtAuthenticationToken authToken =
                    new JwtAuthenticationToken(
                        userDetails,
                        userDetails.getAuthorities()
                    );

                SecurityContextHolder.getContext().setAuthentication(authToken);
            }
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

    // Helper method to get dummy user details for initial validation
    private UserDetails getDummyUserDetails(String jwt) {
        // This creates a basic user detail for initial token validation
        // Actual authorities will be loaded from the database after validation
        String username = jwtUtils.extractUsername(jwt);
        return org.springframework.security.core.userdetails.User
            .withUsername(username)
            .password("") // Empty password for token-based auth
            .authorities("ROLE_USER") // Default authority for validation
            .build();
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