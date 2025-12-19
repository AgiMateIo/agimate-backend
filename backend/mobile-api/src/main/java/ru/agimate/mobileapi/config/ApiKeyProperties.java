package ru.agimate.mobileapi.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "mobile-api.security")
public class ApiKeyProperties {

    private List<ApiKeyEntry> apiKeys;

    @Data
    public static class ApiKeyEntry {
        private String name;
        private String key;
    }
}
