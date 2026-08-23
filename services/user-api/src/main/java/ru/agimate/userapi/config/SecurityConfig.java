package ru.agimate.userapi.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2LoginAuthenticationFilter;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import ru.agimate.common.rest.ErrorResponse;
import ru.agimate.common.security.SecurityUtils;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.userapi.controller.AuthController;
import ru.agimate.userapi.controller.admin.AdminPaths;
import ru.agimate.userapi.security.jwt.JwtDbAuthenticationFilter;
import ru.agimate.userapi.security.InternalAuthFilter;
import ru.agimate.userapi.controller.internal.InternalNotificationController;
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
    private final OAuth2AuthorizationRequestResolver oAuth2AuthorizationRequestResolver;
    private final OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> accessTokenResponseClient;
    private final OAuth2UserService<OAuth2UserRequest, OAuth2User> oAuth2UserService;

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(List.of("*")); // In production, be more specific
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    /**
     * Delegating rather than plain bcrypt: the stored hash carries the id of the algorithm that
     * produced it ({@code {bcrypt}…}), so a future move to another one re-encodes passwords as their
     * owners sign in instead of requiring a migration that cannot exist — the plaintext is gone.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
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

    /**
     * control-api asking to notify someone. A chain of its own, ahead of the main one: the key is not
     * a user token, and the main chain's rules — roles read from the database — have nothing to say
     * about a service.
     */
    @Bean
    @Order(1)
    public SecurityFilterChain internalFilterChain(HttpSecurity http, InternalAuthFilter internalAuthFilter) {
        http.securityMatcher(InternalNotificationController.PATH + "/**")
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptionHandling -> exceptionHandling
                        .authenticationEntryPoint(authenticationEntryPoint())
                        .accessDeniedHandler(accessDeniedHandler()))
                .authorizeHttpRequests(authz -> authz.anyRequest().hasRole(InternalAuthFilter.ROLE_INTERNAL))
                .userDetailsService(new InMemoryUserDetailsManager())
                .addFilterBefore(internalAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    @Order(2)
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
                        .requestMatchers("/", "/oauth2/**").permitAll()
                        // Signing in with a password and asking for one by mail happen before there
                        // is anything to authenticate with. Changing a password does not: it sits
                        // under the rule below, where a session is required.
                        .requestMatchers(AuthController.PATH + "/login",
                                AuthController.PATH + "/register",
                                AuthController.PATH + "/register/confirm",
                                AuthController.PATH + "/register/resend",
                                AuthController.PATH + "/password/forgot",
                                AuthController.PATH + "/password/reset").permitAll()
                        // Changing one's own password is open to GUEST for the same reason the
                        // device list is: an account still awaiting approval has the most reason
                        // to be able to lock somebody out of it.
                        .requestMatchers(AuthController.PATH + "/password/change")
                        .hasAnyRole("USER", "ADMIN", "GUEST")
                        .requestMatchers("/docs/**").permitAll()
                        // Management port 8088 runs this very chain, so permitAll here decides what is
                        // public there. Health and its liveness/readiness groups only — never the rest.
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        // The admin area first: the rules below are broader (/user/** admits GUEST as
                        // well), and the first matching rule wins.
                        .requestMatchers(AdminPaths.PREFIX + "/**").hasRole("ADMIN")
                        // Its own rule because the path sits outside /user/**, where the default
                        // for anything unlisted is USER or ADMIN. Managing one's own devices is
                        // open to GUEST as well.
                        .requestMatchers("/sessions/**").hasAnyRole("USER", "ADMIN", "GUEST")
                        // Подписка на пуши — про то же устройство, что и сессия, и открыта тем же,
                        // кому открыт список устройств.
                        .requestMatchers("/push/**").hasAnyRole("USER", "ADMIN", "GUEST")
                        .requestMatchers("/user/**").hasAnyRole("USER", "ADMIN", "GUEST")
                        .anyRequest().hasAnyRole("USER", "ADMIN")
                )
                .userDetailsService(new InMemoryUserDetailsManager())
                .oauth2Login(oauth2 -> oauth2
                        .authorizationEndpoint(authorization -> authorization
                                .authorizationRequestRepository(authorizationRequestRepository())
                                .authorizationRequestResolver(oAuth2AuthorizationRequestResolver)
                        )
                        .tokenEndpoint(token -> token.accessTokenResponseClient(accessTokenResponseClient))
                        // Applies to the plain OAuth2 providers; Google is OpenID Connect and keeps
                        // the default OidcUserService.
                        .userInfoEndpoint(userInfo -> userInfo.userService(oAuth2UserService))
                        .successHandler(oAuth2SuccessHandler)
                        .failureHandler(oAuth2FailureHandler)
                )
                // Add OAuth2 error intercept filter BEFORE OAuth2LoginAuthenticationFilter
                // This prevents Spring Security from trying to authenticate requests that already have error parameters
                .addFilterBefore(jwtDbAuthenticationFilter, OAuth2LoginAuthenticationFilter.class);

        return http.build();
    }
}