package ru.agimate.controlapi.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Платформенный LLM-провайдер (free-tier без BYOK). Значения приходят из окружения
 * (APP_PLATFORM_LLM_*); все три обязательны для сидинга — иначе bootstrap пропускается.
 * Провайдер всегда OPENAI_COMPATIBLE (воркер исполняет только его).
 */
@Component
@ConfigurationProperties(prefix = "app.platform-llm")
@Getter
@Setter
public class PlatformLlmProperties {

    private String baseUrl;
    private String apiKey;
    private String defaultModel;

    public boolean configured() {
        return notBlank(baseUrl) && notBlank(apiKey) && notBlank(defaultModel);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
