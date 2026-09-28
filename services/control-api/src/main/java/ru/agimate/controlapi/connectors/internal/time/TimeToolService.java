package ru.agimate.controlapi.connectors.internal.time;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;
import ru.agimate.controlapi.connectors.core.ConnectorEnv;
import ru.agimate.controlapi.connectors.core.ConnectorEnvHolder;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.connectors.core.ConnectorSettingsService;
import ru.agimate.controlapi.connectors.core.OwnerRequestGuard;
import ru.agimate.controlapi.connectors.core.annotation.Tool;
import ru.agimate.controlapi.connectors.core.annotation.ToolAnnotations;
import ru.agimate.controlapi.connectors.core.annotation.ToolParam;
import ru.agimate.controlapi.connectors.core.annotation.ToolVisibility;
import ru.agimate.controlapi.connectors.core.dto.JobSpec;
import ru.agimate.controlapi.connectors.core.jobs.ConnectorJobService;
import ru.agimate.controlapi.connectors.core.jobs.JobSchedule;
import ru.agimate.controlapi.database.entities.ConnectorJob;
import ru.agimate.controlapi.database.enums.ConnectorJobType;
import ru.agimate.controlapi.database.repositories.AgentRunRepository;
import ru.agimate.controlapi.service.trigger.ChannelInfo;
import ru.agimate.controlapi.service.trigger.Channels;
import ru.agimate.controlapi.service.trigger.ChannelsCodec;
import ru.agimate.controlapi.service.trigger.Trigger;
import ru.agimate.controlapi.service.trigger.TriggerAudience;
import ru.agimate.controlapi.service.trigger.TriggerContext;
import ru.agimate.controlapi.service.trigger.TriggerRouterService;

import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Tools of the time connector: the current time and scheduling of an agent's deferred jobs.
 *
 * <p>{@code time.schedule} inserts a {@code connector_jobs} row (ONETIME/PERIODIC/CRON); when the
 * deadline arrives, {@code ConnectorJobScheduler} dispatches the hidden {@link #fire} — which raises
 * the trigger {@code due} (agent-facing {@code time.due}) addressed to the initiating agent, and that
 * agent «wakes up».
 */
@Component
@RequiredArgsConstructor
public class TimeToolService {

    /** Name of the hidden dispatcher job and of the trigger sent to the agent. */
    static final String FIRE_TASK = "fire";
    static final String DUE_TRIGGER = "due";

    /** Firing is merely publishing a trigger; the iteration is short. */
    private static final int FIRE_TIMEOUT_SECONDS = 60;

    /** Job arg carrying the scheduling conversation's reply address to {@link #fire}. */
    static final String REPLY_ADDRESS = "replyAddress";

    /** The settings panel: the agent's timezone. */
    public static final String SETTINGS_VIEW = "ui://time/settings";

    private final ConnectorJobService jobService;
    private final TriggerRouterService triggerRouterService;
    private final AgentRunRepository agentRunRepository;
    private final ConnectorSettingsService settingsService;
    private final OwnerRequestGuard ownerRequestGuard;

    @Tool(name = "current_datetime", description = "Get the current date and time in the user's timezone "
            + "(ISO-8601 with the offset and the zone id; UTC while no timezone is set)",
            annotations = @ToolAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = false))
    public Map<String, Object> currentDateTime() {
        TimeSettings settings = settings(ConnectorEnvHolder.current());
        return Map.of(
                "dateTime", settings.format(LocalDateTime.now()),
                "zone", settings.zone().getId());
    }

    @Tool(name = "set_timezone", description = "Set the user's timezone — only when the user tells you where "
            + "they are or asks to change it. Your times, and cron schedules without a zone, follow it. "
            + "Pass UTC to go back to UTC.",
            annotations = @ToolAnnotations(destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public Map<String, Object> setTimezone(@ToolParam("IANA timezone id, e.g. Europe/Moscow") String timezone) {
        ConnectorEnv ctx = ConnectorEnvHolder.current();
        ownerRequestGuard.require(ctx);
        // Weak models send "" for a parameter they mean to skip; here that would silently wipe the zone.
        if (timezone == null || timezone.isBlank()) {
            throw new ConnectorException("timezone is required, e.g. Europe/Moscow (UTC to go back to UTC)");
        }
        return settingsResult(save(ctx, timezone));
    }

    @Tool(name = "get_settings", description = "Get the time settings of this agent",
            annotations = @ToolAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = false),
            visibility = ToolVisibility.VIEW, view = SETTINGS_VIEW)
    public Map<String, Object> getSettings() {
        return settingsResult(settings(ConnectorEnvHolder.current()));
    }

    @Tool(name = "save_settings", description = "Save the time settings of this agent",
            annotations = @ToolAnnotations(destructiveHint = false, idempotentHint = true, openWorldHint = false),
            visibility = ToolVisibility.VIEW)
    public Map<String, Object> saveSettings(
            @ToolParam(value = "IANA timezone id, e.g. Europe/Moscow; empty — UTC", required = false) String timezone) {
        return settingsResult(save(ConnectorEnvHolder.current(), timezone));
    }

    @Tool(name = "schedule",
            description = "Schedule a deferred task for yourself: you will be woken up with the given prompt "
                    + "once after a delay, repeatedly every N seconds, or on a cron schedule. "
                    + "Provide exactly one of: delaySeconds, intervalSeconds, cron.",
            annotations = @ToolAnnotations(destructiveHint = false, openWorldHint = false))
    public Map<String, Object> schedule(
            @ToolParam("What you should be reminded to do when the task fires") String prompt,
            @ToolParam(value = "Run once after this many seconds from now", required = false) Long delaySeconds,
            @ToolParam(value = "Run repeatedly every this many seconds", required = false) Long intervalSeconds,
            @ToolParam(value = "Run on this cron schedule (Spring 6-field, with seconds)", required = false) String cron,
            @ToolParam(value = "Timezone for cron, IANA id (default: the user's timezone)", required = false) String zone) {
        ConnectorEnv ctx = ConnectorEnvHolder.current();
        if (ctx.agentId() == null || ctx.userId() == null) {
            throw new ConnectorException("time.schedule must be called by an agent");
        }
        if (prompt == null || prompt.isBlank()) {
            throw new ConnectorException("prompt is required");
        }

        // Weak OpenAI-shim models do not omit unused optional parameters but send zero values (0, "")
        // instead — we treat those as «no value given», otherwise modes is always > 1.
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
            // Resolved here and stored in the job's config: a later change of the agent's zone must not
            // move a task the user set «at 9 Moscow time».
            String resolvedZone = zone == null || zone.isBlank() ? settings(ctx).zone().getId() : zone;
            firstRunAt = nextCron(cron, resolvedZone, now);
            type = ConnectorJobType.CRON;
            config = JobSchedule.cronConfig(cron, resolvedZone);
        }

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("prompt", prompt);
        Map<String, Object> address = replyAddress(ctx);
        if (address != null) {
            args.put(REPLY_ADDRESS, address);
        }
        JobSpec spec = new JobSpec(FIRE_TASK, type, config, args, FIRE_TIMEOUT_SECONDS);
        // A snapshot of the call's originating channel and prompt session onto the job's row: the reminder
        // will reach the agent with that channel as progress/answer (a reminder has no prompt), and while the
        // session is alive — with the history and the partition of the original conversation. The reply
        // address rides in the args: it is handed back to fire as is, nothing else reads it.
        ConnectorJob row = jobService.schedule(
                TimeConnectorService.CONNECTOR_CODE, ctx.connectionId(), ctx.userId(),
                ctx.agentId(), ctx.channelId(), ctx.sessionId(), spec, firstRunAt);

        return Map.of(
                "id", row.getId().toString(),
                "taskType", type.name(),
                "nextRunAt", settings(ctx).format(firstRunAt));
    }

    @Tool(name = "scheduled_tasks", description = "List your active (not yet completed) scheduled tasks",
            annotations = @ToolAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, Object> scheduledTasks() {
        ConnectorEnv ctx = ConnectorEnvHolder.current();
        if (ctx.agentId() == null || ctx.userId() == null) {
            throw new ConnectorException("time.scheduled_tasks must be called by an agent");
        }
        TimeSettings settings = settings(ctx);
        List<Map<String, Object>> tasks = new ArrayList<>();
        for (ConnectorJob row : jobService.findActiveByAgent(
                TimeConnectorService.CONNECTOR_CODE, ctx.userId(), ctx.agentId())) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.getId().toString());
            item.put("taskType", row.getType().name());
            item.put("nextRunAt", settings.format(row.getNextRunAt()));
            item.put("prompt", row.getArgs() == null ? null : row.getArgs().get("prompt"));
            item.put("config", row.getConfig());
            tasks.add(item);
        }
        return Map.of("tasks", tasks);
    }

    @Tool(name = "cancel_scheduled", description = "Cancel one of your scheduled tasks by id",
            annotations = @ToolAnnotations(destructiveHint = true, openWorldHint = false))
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
     * Hidden dispatch target: executed by the scheduler when a dynamic {@code connector_jobs} row
     * ({@code kind=AGENT}) created by {@link #schedule} comes due. The context is reconstructed from
     * the row (the initiator's {@code userId}/{@code agentId}/{@code channelId}), so the trigger is
     * addressed back to that agent through the audience. {@code visibility = {}} — invisible to the
     * LLM, yet still a target of {@code executeJob}; deliberately NOT {@code @Job}, otherwise
     * reconcile would create a background SYSTEM row with no initiating agent.
     */
    @Tool(name = FIRE_TASK, description = "Internal: deliver a scheduled task to its agent", visibility = {})
    public void fire(@ToolParam("Prompt to deliver to the agent") String prompt,
                     @ToolParam(value = "Reply address of the scheduling conversation", required = false)
                     Map<String, Object> replyAddress) {
        ConnectorEnv ctx = ConnectorEnvHolder.current();
        if (ctx.agentId() == null) {
            throw new ConnectorException("Scheduled task has no originating agent");
        }
        TriggerAudience audience = new TriggerAudience(null, List.of(ctx.agentId()));
        Trigger trigger = Trigger.createDirected(
                TimeConnectorService.CONNECTOR_CODE,
                ctx.connectionId(),
                DUE_TRIGGER,
                Map.of("prompt", firedPrompt(settings(ctx), prompt)),
                fireContext(audience, ctx.channelId(), ctx.sessionId(), replyAddress));
        triggerRouterService.routeTrigger(ctx.userId(), trigger);
    }

    /** The address of the call's conversation, as the calling run's channel snapshot keeps it. */
    private Map<String, Object> replyAddress(ConnectorEnv ctx) {
        if (ctx.runId() == null || ctx.sessionId() == null) {
            return null;
        }
        return agentRunRepository.findById(ctx.runId())
                .map(run -> ChannelsCodec.fromMap(run.getChannels()))
                .map(channels -> Stream.of(channels.prompt(), channels.answer())
                        .filter(slot -> slot != null && ctx.sessionId().equals(slot.sessionId()))
                        .findFirst()
                        .map(ChannelInfo::address)
                        .orElse(null))
                .orElse(null);
    }

    /**
     * Context of the reminder trigger: on top of the audience it adds a proactive reply channel
     * (snapshots of the channel and the prompt session taken from the job's row). {@code prompt} stays
     * {@code null} (there is no incoming message), and the originating channel goes into
     * {@code progress}/{@code answer}; a session closed by the time it fires is replaced by
     * {@code ChannelRouteResolver} with the channel's active session.
     */
    private TriggerContext fireContext(TriggerAudience audience, UUID channelId, UUID sessionId,
                                       Map<String, Object> replyAddress) {
        if (channelId == null) {
            return TriggerContext.audience(audience);
        }
        ChannelInfo ref = new ChannelInfo(channelId, sessionId, null, replyAddress);
        return new TriggerContext(audience, new Channels(null, ref, ref));
    }

    private static void requirePositive(long value, String field) {
        if (value <= 0) {
            throw new ConnectorException(field + " must be positive");
        }
    }

    private static LocalDateTime nextCron(String expr, String zone, LocalDateTime now) {
        if (!CronExpression.isValidExpression(expr)) {
            throw new ConnectorException("Invalid cron expression: " + expr);
        }
        try {
            ZoneId.of(zone);
        } catch (DateTimeException e) {
            throw new ConnectorException("Invalid zone: " + zone);
        }
        LocalDateTime next = JobSchedule.nextCron(expr, zone, now);
        if (next == null) {
            throw new ConnectorException("Cron expression never fires: " + expr);
        }
        return next;
    }

    /**
     * The task's text with the moment it fired: it rides in the event's data, so it stays in the history
     * and reaches a run the same way whether the event starts one or is steered into a running one.
     */
    static String firedPrompt(TimeSettings settings, String prompt) {
        return "Fired at " + settings.format(LocalDateTime.now()) + ".\n\n" + (prompt == null ? "" : prompt);
    }

    private TimeSettings settings(ConnectorEnv ctx) {
        return settingsService.get(ctx.agentId(), ctx.connectionId(), TimeSettings.class);
    }

    private TimeSettings save(ConnectorEnv ctx, String timezone) {
        return settingsService.save(ctx.agentId(), ctx.connectionId(), TimeSettings.of(timezone));
    }

    private static Map<String, Object> settingsResult(TimeSettings settings) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("timezone", settings.timezone());
        result.put("zone", settings.zone().getId());
        result.put("now", settings.format(LocalDateTime.now()));
        return result;
    }
}
