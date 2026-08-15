package ru.agimate.common.security.jwt;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "jwt")
public class JwtProperties {

    private String privateKey;
    private String publicKey;
    private Integer accessExpiration;
    private Integer refreshExpiration;

    /**
     * Lifetimes for sessions of an installed application. An access token is short here because
     * revoking a device only bites once the current one expires — the registry is not consulted on
     * every request. A refresh token is long because reopening the app must not mean signing in
     * again. See docs/decisions/native-auth.md.
     */
    private Integer nativeAccessExpiration;
    private Integer nativeRefreshExpiration;

}
