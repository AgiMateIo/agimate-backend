package ru.agimate.controlapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import ru.agimate.controlapi.config.PlatformLlmProperties;

/**
 * Сидинг платформенного LLM-провайдера при старте (паттерн {@link SystemSkillBootstrap}).
 *
 * <p>Строка в {@code llm_providers} с владельцем {@link SystemSkillBootstrap#SYSTEM_USER_ID} и
 * именем {@link LlmProviderService#PLATFORM_PROVIDER_NAME}; ключ — в {@code secrets}. Окружение
 * задаёт base_url/api_key/default_model, но НЕ {@code enabled}: включение free-tier — runtime-флаг
 * в БД. Без заполненных {@code app.platform-llm.*} сидинг молча пропускается (фича выключена).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlatformLlmBootstrap {

    private final PlatformLlmProperties properties;
    private final LlmProviderService llmProviderService;

    @EventListener(ApplicationReadyEvent.class)
    public void bootstrap() {
        if (!properties.configured()) {
            log.info("Platform LLM provider is not configured (app.platform-llm.*) — skipping seed");
            return;
        }
        try {
            upsert();
        } catch (DataIntegrityViolationException e) {
            // Гонка нод на холодном старте: параллельная нода успела вставить (user_id, name) —
            // повторный вызов уходит update-веткой.
            upsert();
        } catch (Exception e) {
            // Не валим старт приложения: без платформенного провайдера остальное работоспособно.
            log.error("Failed to seed platform LLM provider: {}", e.getMessage(), e);
        }
    }

    private void upsert() {
        llmProviderService.upsertPlatformProvider(
                properties.getBaseUrl(), properties.getApiKey(), properties.getDefaultModel());
    }
}
