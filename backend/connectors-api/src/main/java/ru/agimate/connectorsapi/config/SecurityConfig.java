package ru.agimate.connectorsapi.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import ru.agimate.common.rest.ErrorResponse;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.connectorsapi.controller.api.CallController;
import ru.agimate.connectorsapi.controller.api.MethodController;
import ru.agimate.connectorsapi.controller.manage.ConnectorController;
import ru.agimate.connectorsapi.controller.manage.ConnectorsApiKeyController;
import ru.agimate.connectorsapi.controller.manage.CredentialController;
import ru.agimate.connectorsapi.security.ApiKeyAuthenticationFilter;
import ru.agimate.connectorsapi.security.JwtAuthenticationFilter;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final ApiKeyAuthenticationFilter apiKeyAuthenticationFilter;

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(List.of("*"));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public UserDetailsService userDetailsService() {
        // Empty UserDetailsService - we use JWT and API keys
        return new InMemoryUserDetailsManager();
    }

    @Bean
    public AuthenticationEntryPoint authenticationEntryPoint() {
        return (request, response, authException) -> {
            response.setStatus(UNAUTHORIZED.value());
            response.setContentType("application/json");
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());

            var writer = response.getWriter();
            writer.write(JsonUtils.writeValueAsString(
                    new ErrorResponse("Authentication credentials not found or invalid")));
            writer.flush();
        };
    }

    @Bean
    public AccessDeniedHandler accessDeniedHandler() {
        return (request, response, accessDeniedException) -> {
            response.setStatus(FORBIDDEN.value());
            response.setContentType("application/json");
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());

            var writer = response.getWriter();
            writer.write(JsonUtils.writeValueAsString(
                    new ErrorResponse("Access denied. Insufficient permissions.")));
            writer.flush();
        };
    }

    /**
     * JWT-protected endpoints SecurityFilterChain for dashboard/management operations.
     * Handles /connectors/**, /credentials/**, /auth-keys/** with JWT authentication.
     */
    @Bean
    @Order(1)
    public SecurityFilterChain jwtSecurityFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher(
                ConnectorController.PATH + "/**",
                CredentialController.PATH + "/**",
                ConnectorsApiKeyController.PATH + "/**"
        );

        applyCommonSecurityConfig(http);

        http.authorizeHttpRequests(authz -> authz.anyRequest().authenticated())
                .userDetailsService(userDetailsService())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * API key-protected endpoints SecurityFilterChain for connector method calls.
     * Handles /call/**, /methods/** with API key authentication.
     */
    @Bean
    @Order(2)
    public SecurityFilterChain apiKeySecurityFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher(
                CallController.PATH + "/**",
                MethodController.PATH + "/**"
        );

        applyCommonSecurityConfig(http);

        http.authorizeHttpRequests(authz -> authz.anyRequest().authenticated())
                .userDetailsService(userDetailsService())
                .addFilterBefore(apiKeyAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * Public endpoints SecurityFilterChain for health checks, documentation, etc.
     * Handles all other requests not matched by higher-priority chains.
     */
    @Bean
    @Order(3)
    public SecurityFilterChain publicSecurityFilterChain(HttpSecurity http) throws Exception {
        applyCommonSecurityConfig(http);

        http.authorizeHttpRequests(authz -> authz
                .requestMatchers(
                        "/",
                        "/error",
                        "/favicon.ico",
                        "/docs/**",
                        "/actuator/health"
                ).permitAll()
                .anyRequest().authenticated()
        );

        return http.build();
    }

    /**
     * Apply common security configuration to all SecurityFilterChain beans.
     * Includes CORS, CSRF disable, stateless sessions, and exception handling.
     */
    private void applyCommonSecurityConfig(HttpSecurity http) throws Exception {
        http.cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptionHandling ->
                        exceptionHandling
                                .authenticationEntryPoint(authenticationEntryPoint())
                                .accessDeniedHandler(accessDeniedHandler())
                );
    }
}
