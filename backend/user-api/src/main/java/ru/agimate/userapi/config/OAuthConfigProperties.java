package ru.agimate.userapi.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "app.oauth")
public class OAuthConfigProperties {
    private Map<String, ProviderConfig> providers;

    @Data
    public static class ProviderConfig {
        private String clientId;
        private String clientSecret;
        private String redirectUri;
        private String authorizationUrl;
        private String tokenUrl;
        private String userInfoUrl;
        private boolean active = true;
    }
}