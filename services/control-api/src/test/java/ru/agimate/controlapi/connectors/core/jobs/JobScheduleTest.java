package ru.agimate.controlapi.connectors.core.jobs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import ru.agimate.common.util.UUIDUtils;
import ru.agimate.controlapi.connectors.core.dto.JobSpec;
import ru.agimate.controlapi.database.enums.ConnectorJobType;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("JobSchedule")
class JobScheduleTest {

    private static final long HOUR = 3_600;

    private static JobSpec cronSpec(String cron, long spreadSeconds) {
        return new JobSpec("daily", ConnectorJobType.CRON,
                JobSchedule.cronConfig(cron, JobSchedule.DEFAULT_ZONE, spreadSeconds), Map.of(), 120);
    }

    @Nested
    @DisplayName("offsetFor")
    class OffsetFor {

        @Test
        @DisplayName("один и тот же id → одно и то же смещение")
        void deterministic() {
            UUID id = UUIDUtils.generateUUIDv8();
            assertEquals(JobSchedule.offsetFor(id, HOUR), JobSchedule.offsetFor(id, HOUR));
            assertEquals(JobSchedule.offsetFor(id, HOUR),
                    JobSchedule.offsetFor(UUID.fromString(id.toString()), HOUR));
        }

        @Test
        @DisplayName("смещение внутри окна, включая отрицательные младшие биты")
        void withinWindow() {
            for (int i = 0; i < 1_000; i++) {
                long offset = JobSchedule.offsetFor(UUIDUtils.generateUUIDv8(), HOUR);
                assertTrue(offset >= 0 && offset < HOUR, "offset out of window: " + offset);
            }
        }

        @Test
        @DisplayName("окно заполняется равномерно — пустых минут не остаётся")
        void spreadsAcrossTheWindow() {
            int[] buckets = new int[60];
            for (int i = 0; i < 10_000; i++) {
                buckets[(int) (JobSchedule.offsetFor(UUIDUtils.generateUUIDv8(), HOUR) / 60)]++;
            }
            for (int minute = 0; minute < buckets.length; minute++) {
                assertTrue(buckets[minute] > 0, "no connection landed on minute " + minute);
            }
        }
    }

    @Nested
    @DisplayName("shiftCron")
    class ShiftCron {

        @Test
        @DisplayName("смещение раскладывается по секундам/минутам/часам")
        void shiftsTheTimeFields() {
            assertEquals("17 35 3 * * *", JobSchedule.shiftCron("0 0 3 * * *", 2_117));
        }

        @Test
        @DisplayName("день месяца, месяц и день недели переносятся как есть")
        void keepsTheTail() {
            assertEquals("1 1 3 * * MON", JobSchedule.shiftCron("0 0 3 * * MON", 61));
        }
    }

    @Nested
    @DisplayName("spread")
    class Spread {

        @Test
        @DisplayName("окно → свой cron у экземпляра, объявленный остаётся рядом")
        void shiftsAndKeepsTheBase() {
            UUID connectionId = UUIDUtils.generateUUIDv8();
            Map<String, Object> config = JobSchedule.spread(cronSpec("0 0 3 * * *", HOUR), connectionId).config();

            assertEquals("0 0 3 * * *", config.get(JobSchedule.KEY_BASE_CRON));
            assertEquals(HOUR, JobSchedule.readLong(config, JobSchedule.KEY_SPREAD_SECONDS, 0));
            assertEquals(JobSchedule.shiftCron("0 0 3 * * *", JobSchedule.offsetFor(connectionId, HOUR)),
                    config.get(JobSchedule.KEY_CRON));
        }

        @Test
        @DisplayName("разные коннекции → разные моменты")
        void differsBetweenConnections() {
            Set<Object> moments = new HashSet<>();
            for (int i = 0; i < 20; i++) {
                moments.add(JobSchedule.spread(cronSpec("0 0 3 * * *", HOUR), UUIDUtils.generateUUIDv8())
                        .config().get(JobSchedule.KEY_CRON));
            }
            assertTrue(moments.size() > 1, "every connection landed on the same second");
        }

        @Test
        @DisplayName("повторное применение не накапливает смещение")
        void idempotent() {
            UUID connectionId = UUIDUtils.generateUUIDv8();
            JobSpec once = JobSchedule.spread(cronSpec("0 0 3 * * *", HOUR), connectionId);
            assertEquals(once.config(), JobSchedule.spread(once, connectionId).config());
        }

        @Test
        @DisplayName("без окна спека возвращается той же самой")
        void leavesPlainDeclarationsAlone() {
            JobSpec plain = cronSpec("0 0 3 * * *", 0);
            assertSame(plain, JobSchedule.spread(plain, UUIDUtils.generateUUIDv8()));

            JobSpec periodic = new JobSpec("consolidation", ConnectorJobType.PERIODIC,
                    JobSchedule.periodicConfig(HOUR), Map.of(), 120);
            assertSame(periodic, JobSchedule.spread(periodic, UUIDUtils.generateUUIDv8()));
        }

        @Test
        @DisplayName("карта деклараций разносится целиком")
        void spreadsEveryDeclaration() {
            Map<String, JobSpec> declared = new HashMap<>();
            declared.put("daily", cronSpec("0 0 3 * * *", HOUR));
            UUID connectionId = UUIDUtils.generateUUIDv8();

            Map<String, JobSpec> spread = JobSchedule.spread(declared, connectionId);

            assertEquals("0 0 3 * * *", spread.get("daily").config().get(JobSchedule.KEY_BASE_CRON));
        }
    }

    @Nested
    @DisplayName("requireShiftable")
    class RequireShiftable {

        @Test
        @DisplayName("точка в периоде с окном внутри суток — принимается")
        void acceptsAPointExpression() {
            JobSchedule.requireShiftable("0 0 3 * * *", HOUR);
            JobSchedule.requireShiftable("0 30 23 * * MON", 1_800);
        }

        @Test
        @DisplayName("без окна не проверяется ничего")
        void ignoresDeclarationsWithoutAWindow() {
            JobSchedule.requireShiftable("*/5 * * * * *", 0);
            JobSchedule.requireShiftable(null, 0);
        }

        @Test
        @DisplayName("шаг или звезда во времени — ошибка декларации")
        void rejectsNonPointExpressions() {
            assertThrows(IllegalArgumentException.class,
                    () -> JobSchedule.requireShiftable("*/5 * * * * *", HOUR));
            assertThrows(IllegalArgumentException.class,
                    () -> JobSchedule.requireShiftable("0 0 3-5 * * *", HOUR));
        }

        @Test
        @DisplayName("окно, переходящее через полночь, увело бы задачу на сутки назад")
        void rejectsAWindowCrossingMidnight() {
            assertThrows(IllegalArgumentException.class,
                    () -> JobSchedule.requireShiftable("0 30 23 * * MON", HOUR));
        }

        @Test
        @DisplayName("пятипольное unix-выражение Spring не разбирает — отвергаем на старте")
        void rejectsFiveFieldExpressions() {
            assertThrows(IllegalArgumentException.class,
                    () -> JobSchedule.requireShiftable("0 3 * * *", HOUR));
        }
    }
}
