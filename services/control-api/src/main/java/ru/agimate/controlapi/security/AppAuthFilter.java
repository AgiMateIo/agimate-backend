package ru.agimate.controlapi.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import ru.agimate.controlapi.service.AppKeyAuthService;

import java.io.IOException;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class AppAuthFilter extends OncePerRequestFilter {

    private static final String CONNECTOR_AUTH_KEY_HEADER = "X-App-Auth-Key";

    private final AppKeyAuthService appKeyAuthService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String apiKey = request.getHeader(CONNECTOR_AUTH_KEY_HEADER);

        if (StringUtils.hasText(apiKey)) {
            appKeyAuthService.validateKey(apiKey)
                    .ifPresent(app -> {
                        var authorities = List.of(new SimpleGrantedAuthority("ROLE_APP"));
                        var principal = new AppPrincipal(
                                app.getName(),
                                app.getId(),
                                app.getUserId()
                        );

                        SecurityContextHolder.getContext().setAuthentication(
                                new AppAuthToken(principal, authorities)
                        );

                        log.debug("App key authenticated for app: {} (user: {})",
                                app.getName(), app.getUserId());
                    });
        }

        filterChain.doFilter(request, response);
    }

}
