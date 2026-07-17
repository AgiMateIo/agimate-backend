package ru.agimate.controlapi.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Лимиты входящего трафика от внешних источников (устройства-app и вебхуки), per-connection.
 * Значение {@code <= 0} отключает лимит соответствующего scope.
 */
@Component
@ConfigurationProperties(prefix = "inbound-rate-limit")
@Getter
@Setter
public class InboundRateLimitProperties {
    private boolean enabled = true;
    /** Триггеры ({@code /app/trigger/new}, {@code /webhook/*}) в минуту на connection. */
    private int triggersPerMinute = 120;
    /** Результаты тулов ({@code /app/tools/result}) в минуту на connection. */
    private int toolResultsPerMinute = 120;
    /** Загрузки файлов ({@code /app/files}) в минуту на connection. */
    private int fileUploadsPerMinute = 30;
}
