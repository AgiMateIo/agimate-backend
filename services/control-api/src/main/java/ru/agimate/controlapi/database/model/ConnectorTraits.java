package ru.agimate.controlapi.database.model;

import ru.agimate.controlapi.database.enums.DefinitionBinding;
import ru.agimate.controlapi.database.enums.ExecutionKind;

/**
 * Type-level descriptor of a connector: functional axes only — the ones the mechanics branch on.
 * Declared in {@code ConnectorHandler.traits()} and persisted into the {@code connectors} catalogue
 * by the bootstrap — the code is the source of truth. Other differences between connectors are not
 * declared: instance-bearing is derived ({@code Connector.isInstanceBearing()}: the user brings the
 * instance's identity — credentials or a device registration), and the data-owner rule
 * (agent/team/user) is embodied in each connector's code and described in
 * {@code docs/architecture/connectors.md}.
 *
 * @param executionKind     who executes a tool call — read by {@code ConnectorService.pushToConnector}
 * @param definitionBinding where tool/trigger definitions come from: STATIC (reflection/SPI) or
 *                          DYNAMIC ({@code connection_tools}/{@code connection_triggers}) — read by the listing
 */
public record ConnectorTraits(
        ExecutionKind executionKind,
        DefinitionBinding definitionBinding
) {

    /** The default: backend execution, static tools (internal services and integrations such as telegram). */
    public static ConnectorTraits internal() {
        return new ConnectorTraits(ExecutionKind.BACKEND, DefinitionBinding.STATIC);
    }

    /** An integration with dynamic per-instance tools (MCP): definitions come from {@code connection_tools}. */
    public static ConnectorTraits dynamicIntegration() {
        return new ConnectorTraits(ExecutionKind.BACKEND, DefinitionBinding.DYNAMIC);
    }

    /** A device (app): the device executes, the call is pushed, and tools are dynamic. */
    public static ConnectorTraits device() {
        return new ConnectorTraits(ExecutionKind.DEVICE, DefinitionBinding.DYNAMIC);
    }

    /** Loopback/agent-side (claude-code): the agent executes, control-api only authorises. */
    public static ConnectorTraits loopback() {
        return new ConnectorTraits(ExecutionKind.LOOPBACK, DefinitionBinding.STATIC);
    }
}
