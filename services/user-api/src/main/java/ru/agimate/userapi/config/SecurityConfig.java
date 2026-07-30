package ru.agimate.userapi.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.client.web.OAuth2LoginAuthenticationFilter;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import ru.agimate.common.rest.ErrorResponse;
import ru.agimate.common.security.SecurityUtils;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.userapi.controller.admin.AdminPaths;
import ru.agimate.userapi.security.jwt.JwtDbAuthenticationFilter;
import ru.agimate.userapi.security.oauth2.CookieOAuth2AuthorizationRequestRepository;
import ru.agimate.userapi.security.oauth2.OAuth2FailureHandler;
import ru.agimate.userapi.security.oauth2.OAuth2SuccessHandler;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final OAuth2SuccessHandler oAuth2SuccessHandler;
    private final OAuth2FailureHandler oAuth2FailureHandler;
    private final SecretKey oauth2CookieEncryptionKey;
    private final OAuthProperties oAuthProperties;

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(List.of("*")); // In production, be more specific
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public AuthenticationEntryPoint authenticationEntryPoint() {
        return (request, response, authException) -> {
            response.setStatus(UNAUTHORIZED.value());
            response.setContentType("application/json");
            response.setCharacterEncoding(StandardCharsets.UTF_8);

            // Write JSON response
            var writer = response.getWriter();
            writer.write(JsonUtils.writeValueAsString(new ErrorResponse("Authentication credentials not found or invalid")));
            writer.flush();
        };
    }

    @Bean
    public AccessDeniedHandler accessDeniedHandler() {
        return (request, response, accessDeniedException) -> {
            response.setStatus(FORBIDDEN.value());
            response.setContentType("application/json");
            response.setCharacterEncoding(StandardCharsets.UTF_8);

            // Write JSON response
            var writer = response.getWriter();
            writer.write(JsonUtils.writeValueAsString(new ErrorResponse(SecurityUtils.accessDeniedMessage())));
            writer.flush();
        };
    }

    @Bean
    public AuthorizationRequestRepository<OAuth2AuthorizationRequest> authorizationRequestRepository() {
        // Cookie rather than session: the OAuth2 flow then survives landing on another instance.
        return new CookieOAuth2AuthorizationRequestRepository(
                oauth2CookieEncryptionKey, oAuthProperties.isCookieSecure());
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, @Lazy JwtDbAuthenticationFilter jwtDbAuthenticationFilter) {
        http.cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptionHandling ->
                        exceptionHandling
                                .authenticationEntryPoint(authenticationEntryPoint()) // For authentication failures
                                .accessDeniedHandler(accessDeniedHandler()) // For authorization failures
                )
                .authorizeHttpRequests(authz -> authz
                        .requestMatchers("/", "/oauth2/**", "/waitlist/**").permitAll()
                        .requestMatchers("/docs/**").permitAll()
                        // Management port 8088 runs this very chain, so permitAll here decides what is
                        // public there. Health and its liveness/readiness groups only — never the rest.
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        // The admin area first: the rules below are broader (/user/** admits GUEST as
                        // well), and the first matching rule wins.
                        .requestMatchers(AdminPaths.PREFIX + "/**").hasRole("ADMIN")
                        .requestMatchers("/user/**").hasAnyRole("USER", "ADMIN", "GUEST")
                        .anyRequest().hasAnyRole("USER", "ADMIN")
                )
                .userDetailsService(new InMemoryUserDetailsManager())
                .oauth2Login(oauth2 -> oauth2
                        .authorizationEndpoint(authorization -> authorization
                                .authorizationRequestRepository(authorizationRequestRepository())
                        )
                        .successHandler(oAuth2SuccessHandler)
                        .failureHandler(oAuth2FailureHandler)
                )
                // Add OAuth2 error intercept filter BEFORE OAuth2LoginAuthenticationFilter
                // This prevents Spring Security from trying to authenticate requests that already have error parameters
                .addFilterBefore(jwtDbAuthenticationFilter, OAuth2LoginAuthenticationFilter.class);

        return http.build();
    }
}