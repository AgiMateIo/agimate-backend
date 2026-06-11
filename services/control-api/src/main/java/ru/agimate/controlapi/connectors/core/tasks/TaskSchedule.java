package ru.agimate.controlapi.connectors.core.tasks;

import lombok.experimental.UtilityClass;
import org.springframework.scheduling.support.CronExpression;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;

/**
 * Вычисление следующего запуска по {@code task_config}. Общая логика scheduler'а (очередной тик
 * после итерации) и manage-API (пересчёт при resume, чтобы возобновлённая задача не стреляла
 * «вдогонку» по сроку, прошедшему за время паузы).
 */
@UtilityClass
public class TaskSchedule {

    public static LocalDateTime nextCron(Map<String, Object> config, LocalDateTime now) {
        String expr = (String) config.get("cron");
        if (expr == null || expr.isBlank()) {
            // Без выражения в конфиге cron не запустится — отодвигаем далеко, чтобы не ловить
            // SKIP LOCKED'ом на каждом тике.
            return now.plusYears(10);
        }
        String zoneId = (String) config.getOrDefault("zone", "UTC");
        CronExpression cron = CronExpression.parse(expr);
        var next = cron.next(now.atZone(ZoneId.of(zoneId)));
        return next != null ? next.toLocalDateTime() : now.plusYears(10);
    }

    public static long readLong(Map<String, Object> config, String key, long defaultValue) {
        return config.get(key) instanceof Number n ? n.longValue() : defaultValue;
    }
}
