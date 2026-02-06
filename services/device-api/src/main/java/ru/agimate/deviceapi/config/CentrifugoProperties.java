package ru.agimate.deviceapi.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "centrifugo")
@Getter
@Setter
public class CentrifugoProperties {
    private boolean enabled;
    private String url;
    private String port;
    private String apiKey;
    private String privateKey;
    private String publicKey;
}
