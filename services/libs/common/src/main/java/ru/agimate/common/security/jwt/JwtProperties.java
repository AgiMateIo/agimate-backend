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

}
