package ru.agimate.controlapi.connectors.core.jobs;

import lombok.experimental.UtilityClass;
import org.springframework.scheduling.support.CronExpression;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;

/**
 * Computation of the next run from {@code config}. Logic shared by the scheduler (the next tick after
 * an iteration) and the manage API (recomputation on resume, so a resumed job does not fire «to catch
 * up» on a deadline that passed while it was paused).
 */
@UtilityClass
public class JobSchedule {

    public static final String KEY_INTERVAL_SECONDS = "intervalSeconds";
    public static final String KEY_CRON = "cron";
    public static final String KEY_ZONE = "zone";
    public static final String DEFAULT_ZONE = "UTC";

    /** Config snapshot of a schedule by job type — the single source of shape for declarations (@Job) and agent tools (time.schedule). */
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
            // With no expression in the config a cron will never fire — we push it far out so SKIP LOCKED does
            // not keep picking it up on every tick.
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
