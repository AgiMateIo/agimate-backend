package ru.agimate.controlapi.connectors.internal.time;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;
import ru.agimate.controlapi.connectors.core.ConnectorEnv;
import ru.agimate.controlapi.connectors.core.ConnectorEnvHolder;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.connectors.core.annotation.Tool;
import ru.agimate.controlapi.connectors.core.annotation.ToolAnnotations;
import ru.agimate.controlapi.connectors.core.annotation.ToolParam;
import ru.agimate.controlapi.connectors.core.dto.JobSpec;
import ru.agimate.controlapi.connectors.core.jobs.ConnectorJobService;
import ru.agimate.controlapi.connectors.core.jobs.JobSchedule;
import ru.agimate.controlapi.database.entities.ConnectorJob;
import ru.agimate.controlapi.database.enums.ConnectorJobType;
import ru.agimate.controlapi.service.trigger.ChannelInfo;
import ru.agimate.controlapi.service.trigger.Channels;
import ru.agimate.controlapi.service.trigger.Trigger;
import ru.agimate.controlapi.service.trigger.TriggerAudience;
import ru.agimate.controlapi.service.trigger.TriggerContext;
import ru.agimate.controlapi.service.trigger.TriggerRouterService;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Тулы time-коннектора: текущее время и планирование отложенных задач агента.
 *
 * <p>{@code time.schedule} вставляет строку {@code connector_jobs} (ONETIME/PERIODIC/CRON); когда
 * приходит срок, {@code ConnectorJobScheduler} диспатчит скрытый {@link #fire} — он порождает
 * триггер {@code due} (agent-facing {@code time.due}), адресованный агенту-инициатору (audience), и тот «просыпается».
 */
@Component
@RequiredArgsConstructor
public class TimeToolService {

    /** Имя скрытой таски-диспетчера и триггера агенту. */
    static final String FIRE_TASK = "fire";
    static final String DUE_TRIGGER = "due";

    /** Срабатывание — лишь публикация триггера; итерация короткая. */
    private static final int FIRE_TIMEOUT_SECONDS = 60;

    private final ConnectorJobService jobService;
    private final TriggerRouterService triggerRouterService;

    @Tool(name = "current_datetime", description = "Get the current date and time in UTC (ISO-8601)",
            annotations = @ToolAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = false))
    public Map<String, Object> currentDateTime() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS);
        return Map.of(
                "dateTime", now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                "zone", "UTC");
    }

    @Tool(name = "schedule",
            description = "Schedule a deferred task for yourself: you will be woken up with the given prompt "
                    + "once after a delay, repeatedly every N seconds, or on a cron schedule. "
                    + "Provide exactly one of: delaySeconds, intervalSeconds, cron.",
            annotations = @ToolAnnotations(openWorldHint = false))
    public Map<String, Object> schedule(
            @ToolParam("What you should be reminded to do when the task fires") String prompt,
            @ToolParam(value = "Run once after this many seconds from now", required = false) Long delaySeconds,
            @ToolParam(value = "Run repeatedly every this many seconds", required = false) Long intervalSeconds,
            @ToolParam(value = "Run on this cron schedule (Spring 6-field, with seconds)", required = false) String cron,
            @ToolParam(value = "Timezone for cron, IANA id (default UTC)", required = false) String zone) {
        ConnectorEnv ctx = ConnectorEnvHolder.current();
        if (ctx.agentId() == null || ctx.userId() == null) {
            throw new ConnectorException("time.schedule must be called by an agent");
        }
        if (prompt == null || prompt.isBlank()) {
            throw new ConnectorException("prompt is required");
        }

        // Слабые OpenAI-shim модели не опускают неиспользуемые optional-параметры, а шлют
        // zero-values (0, "") — трактуем их как отсутствие значения, иначе modes всегда > 1.
        delaySeconds = delaySeconds != null && delaySeconds == 0 ? null : delaySeconds;
        intervalSeconds = intervalSeconds != null && intervalSeconds == 0 ? null : intervalSeconds;
        cron = cron != null && cron.isBlank() ? null : cron;

        ConnectorJobType type;
        Map<String, Object> config;
        LocalDateTime firstRunAt;
        LocalDateTime now = LocalDateTime.now();
        int modes = (delaySeconds != null ? 1 : 0) + (intervalSeconds != null ? 1 : 0) + (cron != null ? 1 : 0);
        if (modes != 1) {
            throw new ConnectorException("Provide exactly one of: delaySeconds, intervalSeconds, cron");
        }
        if (delaySeconds != null) {
            requirePositive(delaySeconds, "delaySeconds");
            type = ConnectorJobType.ONETIME;
            config = JobSchedule.onetimeConfig();
            firstRunAt = now.plusSeconds(delaySeconds);
        } else if (intervalSeconds != null) {
            requirePositive(intervalSeconds, "intervalSeconds");
            type = ConnectorJobType.PERIODIC;
            config = JobSchedule.periodicConfig(intervalSeconds);
            firstRunAt = now.plusSeconds(intervalSeconds);
        } else {
            String resolvedZone = zone == null || zone.isBlank() ? JobSchedule.DEFAULT_ZONE : zone;
            firstRunAt = nextCron(cron, resolvedZone, now);
            type = ConnectorJobType.CRON;
            config = JobSchedule.cronConfig(cron, resolvedZone);
        }

        JobSpec spec = new JobSpec(
                FIRE_TASK, type, config, Map.of("prompt", prompt), FIRE_TIMEOUT_SECONDS);
        // Снимок исходного канала и prompt-сессии вызова на строку job: напоминание уйдёт агенту
        // с этим каналом как progress/answer (prompt у напоминания нет), а пока сессия жива —
        // с историей и партицией исходного разговора.
        ConnectorJob row = jobService.schedule(
                TimeConnectorService.CONNECTOR_CODE, ctx.connectionId(), ctx.userId(),
                ctx.agentId(), ctx.channelId(), ctx.sessionId(), spec, firstRunAt);

        return Map.of(
                "id", row.getId().toString(),
                "taskType", type.name(),
                "nextRunAt", firstRunAt.toString());
    }

    @Tool(name = "scheduled_tasks", description = "List your active (not yet completed) scheduled tasks",
            annotations = @ToolAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> scheduledTasks() {
        ConnectorEnv ctx = ConnectorEnvHolder.current();
        if (ctx.agentId() == null || ctx.userId() == null) {
            throw new ConnectorException("time.scheduled_tasks must be called by an agent");
        }
        List<Map<String, Object>> tasks = new ArrayList<>();
        for (ConnectorJob row : jobService.findActiveByAgent(
                TimeConnectorService.CONNECTOR_CODE, ctx.userId(), ctx.agentId())) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.getId().toString());
            item.put("taskType", row.getType().name());
            item.put("nextRunAt", row.getNextRunAt() == null ? null : row.getNextRunAt().toString());
            item.put("prompt", row.getArgs() == null ? null : row.getArgs().get("prompt"));
            item.put("config", row.getConfig());
            tasks.add(item);
        }
        return Map.of("tasks", tasks);
    }

    @Tool(name = "cancel_scheduled", description = "Cancel one of your scheduled tasks by id",
            annotations = @ToolAnnotations(openWorldHint = false))
    public Map<String, Object> cancelScheduled(
            @ToolParam("Id of the scheduled task (from time.schedule / time.scheduled_tasks)") String id) {
        ConnectorEnv ctx = ConnectorEnvHolder.current();
        if (ctx.agentId() == null || ctx.userId() == null) {
            throw new ConnectorException("time.cancel_scheduled must be called by an agent");
        }
        UUID taskId;
        try {
            taskId = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new ConnectorException("Invalid task id: " + id);
        }
        boolean cancelled = jobService.cancel(
                TimeConnectorService.CONNECTOR_CODE, ctx.userId(), ctx.agentId(), taskId);
        if (!cancelled) {
            throw new ConnectorException("Scheduled task not found: " + id);
        }
        return Map.of("cancelled", true, "id", id);
    }

    /**
     * Скрытая цель диспатча: исполняется scheduler'ом по сроку динамической строки {@code connector_jobs}
     * ({@code kind=AGENT}), которую завёл {@link #schedule}. Контекст реконструирован из строки
     * ({@code userId}/{@code agentId}/{@code channelId} инициатора), поэтому адресуем триггер обратно
     * агенту через audience. {@code internal = true} — не видна LLM, но остаётся целью {@code executeJob};
     * намеренно НЕ {@code @Job}, иначе reconcile завёл бы фоновую SYSTEM-строку без агента-инициатора.
     */
    @Tool(name = FIRE_TASK, description = "Internal: deliver a scheduled task to its agent", internal = true)
    public void fire(@ToolParam("Prompt to deliver to the agent") String prompt) {
        ConnectorEnv ctx = ConnectorEnvHolder.current();
        if (ctx.agentId() == null) {
            throw new ConnectorException("Scheduled task has no originating agent");
        }
        TriggerAudience audience = new TriggerAudience(null, List.of(ctx.agentId()));
        Trigger trigger = Trigger.createDirected(
                TimeConnectorService.CONNECTOR_CODE,
                ctx.connectionId(),
                DUE_TRIGGER,
                Map.of("prompt", prompt == null ? "" : prompt),
                fireContext(audience, ctx.channelId(), ctx.sessionId()));
        triggerRouterService.routeTrigger(ctx.userId(), trigger);
    }

    /**
     * Контекст триггера напоминания: к audience добавляет проактивный канал ответа (снимки канала и
     * prompt-сессии из строки job'а). {@code prompt} остаётся {@code null} (входящего сообщения нет),
     * исходный канал кладётся в {@code progress}/{@code answer}; закрытую к сроку сессию
     * {@code ChannelRouteResolver} заменит на активную сессию канала.
     */
    private TriggerContext fireContext(TriggerAudience audience, UUID channelId, UUID sessionId) {
        if (channelId == null) {
            return TriggerContext.audience(audience);
        }
        ChannelInfo ref = new ChannelInfo(channelId, sessionId, null);
        return new TriggerContext(audience, new Channels(null, ref, ref));
    }

    private static void requirePositive(long value, String field) {
        if (value <= 0) {
            throw new ConnectorException(field + " must be positive");
        }
    }

    private static LocalDateTime nextCron(String expr, String zone, LocalDateTime now) {
        CronExpression cron;
        try {
            cron = CronExpression.parse(expr);
        } catch (IllegalArgumentException e) {
            throw new ConnectorException("Invalid cron expression: " + expr);
        }
        ZoneId zoneId;
        try {
            zoneId = ZoneId.of(zone);
        } catch (Exception e) {
            throw new ConnectorException("Invalid zone: " + zone);
        }
        var next = cron.next(now.atZone(zoneId));
        if (next == null) {
            throw new ConnectorException("Cron expression never fires: " + expr);
        }
        return next.toLocalDateTime();
    }
}
