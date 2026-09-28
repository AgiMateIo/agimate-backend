package ru.agimate.controlapi.connectors.internal.time;

import org.springframework.stereotype.Component;
import ru.agimate.controlapi.connectors.core.BaseConnectorHandler;
import ru.agimate.controlapi.connectors.core.ClasspathViewProvider;
import ru.agimate.controlapi.connectors.core.ConnectorEnv;
import ru.agimate.controlapi.connectors.core.ConnectorSettingsService;
import ru.agimate.controlapi.connectors.core.InternalConnectorHandler;
import ru.agimate.controlapi.connectors.core.PromptBlockProvider;
import ru.agimate.controlapi.connectors.core.TriggerProvider;
import ru.agimate.controlapi.connectors.core.dto.ContextDirectives;
import ru.agimate.controlapi.connectors.core.dto.PromptBlock;
import ru.agimate.controlapi.connectors.core.dto.TriggerSpec;

import java.util.List;
import java.util.Map;

/**
 * Facade of the time connector: the current time plus scheduling of an agent's deferred jobs. The
 * tools and the hidden dispatcher job live in {@link TimeToolService}; the single trigger is
 * {@code due} (agent-facing {@code time.due}) — a scheduled job's deadline — addressed to the
 * initiating agent.
 *
 * <p><b>The data owner is the calling agent</b>: jobs are filtered and cancelled by
 * {@code env.agentId}, and the job's row carries a snapshot of its initiator (see the axis checklist
 * in docs/architecture/connectors.md).
 *
 * <p>The agent's timezone is this connector's setting on the binding ({@link TimeSettings}): the SYSTEM
 * block {@code timezone} tells the model which clock its times are in, and the panel
 * ({@link TimeToolService#SETTINGS_VIEW}) lets the owner set it.
 */
@Component
public class TimeConnectorService extends BaseConnectorHandler
        implements InternalConnectorHandler, TriggerProvider, PromptBlockProvider, ClasspathViewProvider {

    public static final String CONNECTOR_CODE = "time";

    public static final String TIMEZONE_BLOCK = "timezone";

    private final ConnectorSettingsService settingsService;

    public TimeConnectorService(TimeToolService toolService, ConnectorSettingsService settingsService) {
        super(toolService);
        this.settingsService = settingsService;
    }

    @Override
    public String connectorCode() {
        return CONNECTOR_CODE;
    }

    @Override
    public String connectorName() {
        return "Time";
    }

    @Override
    public String connectorDescription() {
        return "Current time and deferred tasks: the agent schedules an action for the "
                + "future and comes back to it on time.";
    }

    @Override
    public Map<String, TriggerSpec> getTriggers() {
        // PROMPT is legitimate here: data.prompt is assembled by our own fire() from the job's row, so the text is authored by the agent itself.
        return Map.of(TimeToolService.DUE_TRIGGER, new TriggerSpec(
                "A scheduled task created via time.schedule is due", List.of("prompt"),
                ContextDirectives.builder()
                        .presentation(ContextDirectives.Presentation.PROMPT)
                        .promptParam("prompt")
                        .guidance("Below is the text of a deferred task you scheduled earlier "
                                + "through time.schedule. Carry it out.")
                        .ownConnectionTools(true)
                        .build()));
    }

    /** Stable: it changes only when the zone does, so the cached prompt prefix survives every other run. */
    @Override
    public List<PromptBlock> promptBlocks(ConnectorEnv env) {
        if (env.agentId() == null) {
            return List.of();
        }
        TimeSettings settings = settingsService.get(env.agentId(), env.connectionId(), TimeSettings.class);
        String content = settings.timezone() == null
                ? "The user's timezone is not set, so your times are in UTC. When the user tells you "
                        + "where they are, save it with set_timezone."
                : "The user's timezone is " + settings.timezone() + ". Your times are in it, and a cron "
                        + "schedule without a zone runs in it.";
        return List.of(PromptBlock.system(TIMEZONE_BLOCK, content, Map.of()));
    }
}
