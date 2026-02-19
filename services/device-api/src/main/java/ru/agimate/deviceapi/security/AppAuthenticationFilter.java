package ru.agimate.deviceapi.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import ru.agimate.deviceapi.service.AppService;

import java.io.IOException;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class AppAuthenticationFilter extends OncePerRequestFilter {

    private static final String APP_AUTH_KEY_HEADER = "X-App-Auth-Key";
    private static final String X_FORWARDED_FOR = "X-Forwarded-For";

    private final AppService appService;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {

        String apiKey = request.getHeader(APP_AUTH_KEY_HEADER);

        if (StringUtils.hasText(apiKey)) {
            String clientIp = getClientIp(request);

            appService.validateKeyAndRecordUsage(apiKey, clientIp)
                    .ifPresent(app -> {
                        var authorities = List.of(new SimpleGrantedAuthority("ROLE_DEVICE"));
                        var principal = new AppPrincipal(
                                app.getName(),
                                app.getPubId(),
                                app.getUserPubId()
                        );

                        SecurityContextHolder.getContext().setAuthentication(
                                new AppAuthenticationToken(principal, authorities)
                        );

                        log.debug("API key authenticated for app: {} (user: {})",
                                app.getName(), app.getUserPubId());
                    });
        }

        filterChain.doFilter(request, response);
    }

    private String getClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader(X_FORWARDED_FOR);
        if (StringUtils.hasText(xForwardedFor)) {
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

}
