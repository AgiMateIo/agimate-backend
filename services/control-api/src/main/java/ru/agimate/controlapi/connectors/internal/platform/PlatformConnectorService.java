package ru.agimate.controlapi.connectors.internal.platform;

import org.springframework.stereotype.Component;
import ru.agimate.controlapi.connectors.core.BaseConnectorHandler;
import ru.agimate.controlapi.connectors.core.InternalConnectorHandler;

/**
 * Facade of the platform connector: the tools that manage the platform live in
 * {@link PlatformToolService} (dispatched by reflection through {@link BaseConnectorHandler}). There
 * are no triggers and no jobs — the connector is purely imperative. It is bound to the meta-agent by
 * the skill {@code platform-admin} ({@code connectorCodes: [platform]}); the owner of the operations
 * is {@code env.userId} (the human who owns the agent).
 */
@Component
public class PlatformConnectorService extends BaseConnectorHandler implements InternalConnectorHandler {

    public static final String CONNECTOR_CODE = "platform";

    public PlatformConnectorService(PlatformToolService toolService) {
        super(toolService);
    }

    @Override
    public String connectorCode() {
        return CONNECTOR_CODE;
    }

    @Override
    public String connectorName() {
        return "Platform";
    }

    @Override
    public String connectorDescription() {
        return "Managing the platform from a conversation: the agent creates other agents, "
                + "skills, connections and schedules instead of you configuring them by hand.";
    }
}
