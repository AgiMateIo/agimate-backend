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
import ru.agimate.deviceapi.service.ConnectorKeyAuthService;

import java.io.IOException;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class ConnectorAuthFilter extends OncePerRequestFilter {

    private static final String CONNECTOR_AUTH_KEY_HEADER = "X-App-Auth-Key";

    private final ConnectorKeyAuthService connectorKeyAuthService;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {

        String apiKey = request.getHeader(CONNECTOR_AUTH_KEY_HEADER);

        if (StringUtils.hasText(apiKey)) {
            connectorKeyAuthService.validateKey(apiKey)
                    .ifPresent(connector -> {
                        var authorities = List.of(new SimpleGrantedAuthority("ROLE_CONNECTOR"));
                        var principal = new ConnectorPrincipal(
                                connector.getName(),
                                connector.getPubId(),
                                connector.getUserPubId()
                        );

                        SecurityContextHolder.getContext().setAuthentication(
                                new ConnectorAuthToken(principal, authorities)
                        );

                        log.debug("Connector key authenticated for connector: {} (user: {})",
                                connector.getName(), connector.getUserPubId());
                    });
        }

        filterChain.doFilter(request, response);
    }

}
