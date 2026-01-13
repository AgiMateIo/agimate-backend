package ru.agimate.userapi.security.oauth2;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import ru.agimate.common.rest.ErrorResponse;
import ru.agimate.common.util.JsonUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Filter that intercepts OAuth2 callback requests with error parameters
 * BEFORE Spring Security's OAuth2LoginAuthenticationFilter tries to process them.
 * This prevents authentication attempts on error callbacks.
 *
 * Uses cookie-based storage (HttpCookieOAuth2AuthorizationRequestRepository) for stateless architecture.
 */
@Component
@Slf4j
public class OAuth2ErrorInterceptFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {

        // Check if this is an OAuth2 callback with error parameter
        String error = request.getParameter("error");
        String errorDescription = request.getParameter("error_description");

        if (error != null) {
            // Log the OAuth2 error
            log.error("OAuth2 callback error intercepted. Error: {}, Description: {}, Path: {}",
                    error, errorDescription, request.getRequestURI());

            // CRITICAL: Remove OAuth2AuthorizationRequest cookies
            // CookieOAuth2AuthorizationRequestRepository uses cookies to store OAuth2AuthorizationRequest
            // This prevents OAuth2LoginAuthenticationFilter from trying to process this error callback
            Cookie[] cookies = request.getCookies();
            if (cookies != null) {
                for (Cookie cookie : cookies) {
                    if (CookieOAuth2AuthorizationRequestRepository.OAUTH2_AUTHORIZATION_REQUEST_COOKIE_NAME.equals(cookie.getName())) {
                        Cookie deleteCookie = new Cookie(cookie.getName(), "");
                        deleteCookie.setPath("/");
                        deleteCookie.setMaxAge(0);
                        deleteCookie.setHttpOnly(true);
                        response.addCookie(deleteCookie);
                        log.debug("Removed OAuth2 cookie: {}", cookie.getName());
                    }
                }
            }

            // Return JSON error response immediately without further processing
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType("application/json");
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());

            String errorMessage = String.format("OAuth2 authentication failed. Error: %s, Description: %s",
                    error,
                    errorDescription != null ? errorDescription : "no description");

            response.getWriter().write(JsonUtils.writeValueAsString(new ErrorResponse(errorMessage)));
            response.getWriter().flush();

            // Do NOT continue the filter chain - stop here
            return;
        }

        // No error parameter - continue with normal OAuth2 flow
        filterChain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Only apply this filter to OAuth2 callback endpoints
        String path = request.getServletPath();
        return !path.startsWith("/login/oauth2/code/");
    }
}
