package ru.agimate.controlapi.config;

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
import ru.agimate.common.security.SecurityUtils;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.controlapi.controller.agent.AgentController;
import ru.agimate.controlapi.controller.app.AppRegistrationController;
import ru.agimate.controlapi.controller.manage.ManageAgentController;
import ru.agimate.controlapi.controller.manage.ManageAgentSkillController;
import ru.agimate.controlapi.controller.manage.ManageAgenticTeamController;
import ru.agimate.controlapi.controller.manage.ManageBoardController;
import ru.agimate.controlapi.controller.manage.ManageCentrifugoTokenController;
import ru.agimate.controlapi.controller.manage.ManageChannelController;
import ru.agimate.controlapi.controller.manage.ManageAppsController;
import ru.agimate.controlapi.controller.manage.ManageToolCallLogsController;
import ru.agimate.controlapi.controller.manage.ManageTriggerLogsController;
import ru.agimate.controlapi.controller.manage.ManageAgentConnectionPolicyController;
import ru.agimate.controlapi.controller.manage.ManageConnectorController;
import ru.agimate.controlapi.controller.manage.ManageConnectorJobController;
import ru.agimate.controlapi.controller.manage.ManageConnectionController;
import ru.agimate.controlapi.controller.manage.ManageLlmProviderController;
import ru.agimate.controlapi.controller.manage.ManageSkillController;
import ru.agimate.controlapi.controller.manage.ManageToolsController;
import ru.agimate.controlapi.controller.manage.ManageWebhookDeliveryLogsController;
import ru.agimate.controlapi.controller.webhook.ConnectionWebhookController;
import ru.agimate.controlapi.security.AgentAuthFilter;
import ru.agimate.controlapi.security.AppAuthFilter;
import ru.agimate.controlapi.security.JwtAuthenticationFilter;

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
    private final AppAuthFilter appAuthFilter;
    private final AgentAuthFilter agentAuthFilter;

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(List.of("*"));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
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
                    new ErrorResponse(SecurityUtils.accessDeniedMessage())));
            writer.flush();
        };
    }

    @Bean
    @Order(1)
    public SecurityFilterChain jwtSecurityFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher(
                ManageAppsController.PATH + "/**",
                ManageTriggerLogsController.PATH + "/**",
                ManageToolCallLogsController.PATH + "/**",
                ManageAgentController.PATH + "/**",
                ManageAgentSkillController.PATH + "/**",
                ManageAgentConnectionPolicyController.PATH + "/**",
                ManageAgenticTeamController.PATH + "/**",
                ManageWebhookDeliveryLogsController.PATH + "/**",
                ManageConnectionController.PATH + "/**",
                ManageBoardController.PATH + "/**",
                ManageSkillController.PATH + "/**",
                ManageConnectorController.PATH + "/**",
                ManageConnectorJobController.PATH + "/**",
                ManageCentrifugoTokenController.PATH + "/**",
                ManageLlmProviderController.PATH + "/**",
                ManageChannelController.PATH + "/**",
                ManageToolsController.PATH + "/**"
        );

        applyCommonSecurityConfig(http);

        http.authorizeHttpRequests(authz -> authz.anyRequest().hasAnyRole("USER", "ADMIN"))
                .userDetailsService(userDetailsService())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain appAuthKeySecurityFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher(
                AppRegistrationController.PATH + "/**"
        );

        applyCommonSecurityConfig(http);

        http.authorizeHttpRequests(authz -> authz.anyRequest().authenticated())
                .userDetailsService(userDetailsService())
                .addFilterBefore(appAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    @Order(3)
    public SecurityFilterChain apiKeySecurityFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher(
                AgentController.PATH + "/**"
        );

        applyCommonSecurityConfig(http);

        http.authorizeHttpRequests(authz -> authz.anyRequest().authenticated())
                .userDetailsService(userDetailsService())
                .addFilterBefore(agentAuthFilter, UsernamePasswordAuthenticationFilter.class);

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
                        "/actuator/health",
                        ConnectionWebhookController.PATH + "/**"
                ).permitAll()
                .anyRequest().authenticated()
        );

        return http.build();
    }

    @Bean
    public FilterRegistrationBean<AppAuthFilter> disableAppAuthFilterAutoRegistration() {
        FilterRegistrationBean<AppAuthFilter> registration = new FilterRegistrationBean<>(appAuthFilter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<AgentAuthFilter> disableAgentAuthFilterAutoRegistration() {
        FilterRegistrationBean<AgentAuthFilter> registration = new FilterRegistrationBean<>(agentAuthFilter);
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
