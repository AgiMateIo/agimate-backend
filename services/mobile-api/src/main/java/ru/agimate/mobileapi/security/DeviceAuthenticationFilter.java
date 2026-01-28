package ru.agimate.mobileapi.security;

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
import ru.agimate.mobileapi.service.DeviceAuthKeyService;

import java.io.IOException;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class DeviceAuthenticationFilter extends OncePerRequestFilter {

    private static final String DEVICE_AUTH_KEY_HEADER = "X-Device-Auth-Key";
    private static final String X_FORWARDED_FOR = "X-Forwarded-For";

    private final DeviceAuthKeyService deviceAuthKeyService;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {

        String apiKey = request.getHeader(DEVICE_AUTH_KEY_HEADER);

        if (StringUtils.hasText(apiKey)) {
            String clientIp = getClientIp(request);

            deviceAuthKeyService.validateKeyAndRecordUsage(apiKey, clientIp)
                    .ifPresent(deviceAuthKey -> {
                        var authorities = List.of(new SimpleGrantedAuthority("ROLE_DEVICE"));
                        var principal = new DevicePrincipal(
                                deviceAuthKey.getName(),
                                deviceAuthKey.getPubId(),
                                deviceAuthKey.getUserPubId()
                        );

                        SecurityContextHolder.getContext().setAuthentication(
                                new DeviceAuthenticationToken(principal, authorities)
                        );

                        log.debug("API key authenticated for device: {} (user: {})",
                                deviceAuthKey.getName(), deviceAuthKey.getUserPubId());
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

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String servletPath = request.getServletPath();
        return !servletPath.startsWith("/device");
    }
}
