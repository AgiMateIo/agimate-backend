package ru.agimate.controlapi.connectors.core.jobs;

import lombok.experimental.UtilityClass;
import org.springframework.scheduling.support.CronExpression;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;

/**
 * Вычисление следующего запуска по {@code config}. Общая логика scheduler'а (очередной тик
 * после итерации) и manage-API (пересчёт при resume, чтобы возобновлённая задача не стреляла
 * «вдогонку» по сроку, прошедшему за время паузы).
 */
@UtilityClass
public class JobSchedule {

    public static final String KEY_INTERVAL_SECONDS = "intervalSeconds";
    public static final String KEY_CRON = "cron";
    public static final String KEY_ZONE = "zone";
    public static final String DEFAULT_ZONE = "UTC";

    /** Config-снимок расписания по типу задачи — единый источник формы для деклараций (@Job) и агентских тулов (time.schedule). */
    public static Map<String, Object> onetimeConfig() {
        return Map.of();
    }

    public static Map<String, Object> periodicConfig(long intervalSeconds) {
        return Map.of(KEY_INTERVAL_SECONDS, intervalSeconds);
    }

    public static Map<String, Object> cronConfig(String cron, String zone) {
        return Map.of(KEY_CRON, cron, KEY_ZONE, zone);
    }

    public static LocalDateTime nextCron(Map<String, Object> config, LocalDateTime now) {
        String expr = (String) config.get(KEY_CRON);
        if (expr == null || expr.isBlank()) {
            // Без выражения в конфиге cron не запустится — отодвигаем далеко, чтобы не ловить
            // SKIP LOCKED'ом на каждом тике.
            return now.plusYears(10);
        }
        String zoneId = (String) config.getOrDefault(KEY_ZONE, DEFAULT_ZONE);
        CronExpression cron = CronExpression.parse(expr);
        var next = cron.next(now.atZone(ZoneId.of(zoneId)));
        return next != null ? next.toLocalDateTime() : now.plusYears(10);
    }

    public static long readLong(Map<String, Object> config, String key, long defaultValue) {
        return config.get(key) instanceof Number n ? n.longValue() : defaultValue;
    }
}
