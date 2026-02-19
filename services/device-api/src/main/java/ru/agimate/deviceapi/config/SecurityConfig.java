package ru.agimate.deviceapi.config;

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
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import ru.agimate.common.rest.ErrorResponse;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.deviceapi.controller.api.ApiAppsController;
import ru.agimate.common.security.apikey.ApiKeyAuthenticationFilter;
import ru.agimate.deviceapi.controller.app.DeviceToolsController;
import ru.agimate.deviceapi.controller.app.DeviceCentrifugoTokenController;
import ru.agimate.deviceapi.controller.app.DeviceRegistrationController;
import ru.agimate.deviceapi.controller.app.DeviceTriggerController;
import ru.agimate.deviceapi.controller.manage.ManageAgentSettingsController;
import ru.agimate.deviceapi.controller.manage.ManageAppsController;
import ru.agimate.deviceapi.controller.manage.ManageDeviceToolsController;
import ru.agimate.deviceapi.controller.manage.ManageDeviceTriggersController;
import ru.agimate.deviceapi.controller.manage.ManageToolUseLogsController;
import ru.agimate.deviceapi.controller.manage.ManageTriggerLogsController;
import ru.agimate.deviceapi.security.AppAuthenticationFilter;
import ru.agimate.deviceapi.security.JwtAuthenticationFilter;

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
    private final AppAuthenticationFilter appAuthenticationFilter;
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

    @Bean
    @Order(1)
    public SecurityFilterChain jwtSecurityFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher(
                ManageAppsController.PATH + "/**",
                ManageDeviceToolsController.PATH + "/**",
                ManageDeviceTriggersController.PATH + "/**",
                ManageTriggerLogsController.PATH + "/**",
                ManageToolUseLogsController.PATH + "/**",
                ManageAgentSettingsController.PATH + "/**"
        );

        applyCommonSecurityConfig(http);

        http.authorizeHttpRequests(authz -> authz.anyRequest().authenticated())
                .userDetailsService(userDetailsService())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain deviceAuthKeySecurityFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher(
                DeviceToolsController.PATH + "/**",
                DeviceTriggerController.PATH + "/**",
                DeviceRegistrationController.PATH + "/**",
                DeviceCentrifugoTokenController.PATH + "/**"
        );

        applyCommonSecurityConfig(http);

        http.authorizeHttpRequests(authz -> authz.anyRequest().authenticated())
                .userDetailsService(userDetailsService())
                .addFilterBefore(appAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    @Order(3)
    public SecurityFilterChain apiKeySecurityFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher(
                ApiAppsController.PATH + "/**"
        );

        applyCommonSecurityConfig(http);

        http.authorizeHttpRequests(authz -> authz.anyRequest().authenticated())
                .userDetailsService(userDetailsService())
                .addFilterBefore(apiKeyAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    @Order(4)
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

    @Bean
    public FilterRegistrationBean<AppAuthenticationFilter> disableAppAuthFilterAutoRegistration() {
        FilterRegistrationBean<AppAuthenticationFilter> registration = new FilterRegistrationBean<>(appAuthenticationFilter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<ApiKeyAuthenticationFilter> disableApiKeyAuthFilterAutoRegistration() {
        FilterRegistrationBean<ApiKeyAuthenticationFilter> registration = new FilterRegistrationBean<>(apiKeyAuthenticationFilter);
        registration.setEnabled(false);
        return registration;
    }

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
