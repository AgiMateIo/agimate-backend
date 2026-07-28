package ru.agimate.controlapi.connectors.internal.time;

import org.springframework.stereotype.Component;
import ru.agimate.controlapi.connectors.core.BaseConnectorHandler;
import ru.agimate.controlapi.connectors.core.InternalConnectorHandler;
import ru.agimate.controlapi.connectors.core.TriggerProvider;
import ru.agimate.controlapi.connectors.core.dto.ContextDirectives;
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
 */
@Component
public class TimeConnectorService extends BaseConnectorHandler
        implements InternalConnectorHandler, TriggerProvider {

    public static final String CONNECTOR_CODE = "time";

    public TimeConnectorService(TimeToolService toolService) {
        super(toolService);
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
}
