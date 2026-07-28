package ru.agimate.controlapi.connectors.core;

import ru.agimate.controlapi.database.model.ConnectorTraits;

/**
 * The identity core of a connector's SPI — shared by internal and integration connectors.
 *
 * <p>A connector is a composition: the facade implements this interface plus whichever capability
 * interfaces it needs ({@link ToolProvider}, {@link TriggerProvider}, {@link JobProvider},
 * {@link PromptBlockProvider}). Tools and jobs usually arrive through {@link BaseConnectorHandler}
 * (reflection dispatch over the tool service's {@code @Tool} methods). Consumers obtain a capability
 * through {@link ConnectorRegistry#findCapability}/{@link ConnectorRegistry#capability}.
 */
public interface ConnectorHandler {

    String connectorCode();

    default String connectorName() {
        return connectorCode();
    }

    /**
     * Description for the connections catalogue — one phrase about what the connector gives the user
     * (not how it works inside). The code is the source of truth; the bootstrap persists it into
     * {@code connectors}.
     */
    default String connectorDescription() {
        return null;
    }

    /**
     * Type-level descriptor of the connector — functional axes only (see {@link ConnectorTraits}).
     * The code is the source of truth; the bootstrap persists it into the {@code connectors}
     * catalogue. The default is internal (backend execution, static tools) — which also suits
     * integrations such as telegram; only connectors with a different execution kind or with dynamic
     * definitions override it.
     */
    default ConnectorTraits traits() {
        return ConnectorTraits.internal();
    }
}
