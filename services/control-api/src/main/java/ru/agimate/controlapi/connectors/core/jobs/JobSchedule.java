package ru.agimate.controlapi.connectors.core.jobs;

import lombok.experimental.UtilityClass;
import org.springframework.scheduling.support.CronExpression;
import ru.agimate.controlapi.connectors.core.dto.JobSpec;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Computation of the next run from {@code config}. Logic shared by the scheduler (the next tick after
 * an iteration) and the manage API (recomputation on resume, so a resumed job does not fire «to catch
 * up» on a deadline that passed while it was paused).
 *
 * <p>The shape of {@code config} lives here too, declarations included — which is why {@link #spread}
 * sits beside it: a windowed cron is a declaration turned into one instance's own expression, and by
 * the time the scheduler reads the row there is nothing left to know about the window.
 */
@UtilityClass
public class JobSchedule {

    public static final String KEY_INTERVAL_SECONDS = "intervalSeconds";
    public static final String KEY_CRON = "cron";
    public static final String KEY_ZONE = "zone";
    public static final String KEY_BASE_CRON = "baseCron";
    public static final String KEY_SPREAD_SECONDS = "spreadSeconds";
    public static final String DEFAULT_ZONE = "UTC";

    private static final int CRON_FIELDS = 6;
    private static final long SECONDS_PER_DAY = 24 * 60 * 60L;

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

    /** The declared form of a spread cron: {@code cron} is still the base, the window travels beside it. */
    public static Map<String, Object> cronConfig(String cron, String zone, long spreadSeconds) {
        return spreadSeconds <= 0
                ? cronConfig(cron, zone)
                : Map.of(KEY_CRON, cron, KEY_ZONE, zone, KEY_SPREAD_SECONDS, spreadSeconds);
    }

    /**
     * Materialises a declaration for one connector instance: a job with a spread window gets a cron of
     * its own, somewhere inside the window, and keeps it — the offset is derived from the connection id
     * rather than drawn anew, so a restart or a re-sync lands on the same second. Without this every
     * row of every installation would come due at the one moment the declaration names, and the whole
     * install would wake up together.
     *
     * <p>The shift is always applied to the base from the declaration, never to what is already stored,
     * so re-syncing a row does not accumulate offsets.
     */
    public static Map<String, JobSpec> spread(Map<String, JobSpec> declared, UUID connectionId) {
        return declared.values().stream()
                .map(spec -> spread(spec, connectionId))
                .collect(Collectors.toMap(JobSpec::name, Function.identity(), (a, b) -> b, LinkedHashMap::new));
    }

    public static JobSpec spread(JobSpec spec, UUID connectionId) {
        long window = readLong(spec.config(), KEY_SPREAD_SECONDS, 0);
        // The base, not the shifted expression, so applying this twice cannot accumulate offsets.
        String base = (String) spec.config().getOrDefault(KEY_BASE_CRON, spec.config().get(KEY_CRON));
        if (window <= 0 || connectionId == null || base == null || base.isBlank()) {
            return spec;
        }
        Map<String, Object> config = new LinkedHashMap<>(spec.config());
        config.put(KEY_CRON, shiftCron(base, offsetFor(connectionId, window)));
        config.put(KEY_BASE_CRON, base);
        return new JobSpec(spec.name(), spec.type(), config, spec.args(), spec.timeoutSeconds());
    }

    /**
     * The instance's offset inside the window — a function of the id, not a draw: a fresh random number
     * would be re-rolled by every startup re-sync and the job would wander across the window on each
     * deploy. The low 64 bits of a UUIDv8 are 46 random bits plus a checksum over the rest, which is
     * the part of the identifier worth hashing.
     */
    public static long offsetFor(UUID connectionId, long spreadSeconds) {
        return Math.floorMod(connectionId.getLeastSignificantBits(), spreadSeconds);
    }

    /** Moves the second/minute/hour of a point expression by {@code offsetSeconds}; the rest is copied over. */
    public static String shiftCron(String cron, long offsetSeconds) {
        String[] fields = cron.trim().split("\\s+");
        long secondOfDay = secondOfDay(fields) + offsetSeconds;
        fields[0] = String.valueOf(secondOfDay % 60);
        fields[1] = String.valueOf(secondOfDay / 60 % 60);
        fields[2] = String.valueOf(secondOfDay / 3600);
        return String.join(" ", fields);
    }

    /**
     * Rejects an expression the window cannot be applied to. The shift moves second/minute/hour, so
     * those three have to name one point — a range or a step would silently turn into a different
     * schedule — and the window has to stay inside the same day, or the job would land before the
     * moment it was declared with (a 23:30 job shifted past midnight fires at 00:05, 23 hours early).
     *
     * @throws IllegalArgumentException with the reason; the caller adds the declaration's identity
     */
    public static void requireShiftable(String cron, long spreadSeconds) {
        if (spreadSeconds <= 0) {
            return;
        }
        String[] fields = cron == null ? new String[0] : cron.trim().split("\\s+");
        if (fields.length != CRON_FIELDS) {
            throw new IllegalArgumentException(
                    "spreadSeconds needs a six-field cron expression, got: " + cron);
        }
        long secondOfDay;
        try {
            secondOfDay = secondOfDay(fields);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("spreadSeconds needs plain second/minute/hour fields "
                    + "(a single point in the period), got: " + cron, e);
        }
        if (secondOfDay < 0 || secondOfDay + spreadSeconds > SECONDS_PER_DAY) {
            throw new IllegalArgumentException("the spread window must stay inside the day: "
                    + cron + " + " + spreadSeconds + "s");
        }
    }

    private static long secondOfDay(String[] fields) {
        return Integer.parseInt(fields[0])
                + Integer.parseInt(fields[1]) * 60L
                + Integer.parseInt(fields[2]) * 3600L;
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
