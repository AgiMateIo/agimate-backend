package ru.agimate.controlapi.connectors.internal.platform.dto;

import java.util.List;
import java.util.Map;

/**
 * View models of the connector/connection tools of the platform connector
 * ({@code PlatformConnectionToolService}). Flat and LLM-friendly (public ids as strings). See
 * {@link PlatformDtos} for the shared-file rules; this file holds only the records the connections
 * module owns.
 */
public final class PlatformConnectionDtos {

    private PlatformConnectionDtos() {
    }

    public record ConnectorBrief(String code, String name, String description, boolean integration) {
    }

    public record ConnectorList(List<ConnectorBrief> connectors, boolean truncated) {
    }

    /**
     * {@code inputSchema} is the tool's JSON Schema of arguments (null when the tool declares none).
     * It is what a {@code params_filter} in an ABAC policy is written against.
     */
    public record ToolBrief(String name, String description, Map<String, Object> inputSchema) {
    }

    public record TriggerBrief(String name, String description) {
    }

    public record ConnectorDetail(String code, String name, String description, boolean integration,
                                  List<ToolBrief> tools, List<TriggerBrief> triggers) {
    }

    /**
     * {@code authStatus} is here and not omitted for brevity: with only {@code enabled} in sight the
     * meta-agent takes a connection awaiting authorization for a working one and cheerfully reports
     * to the user that everything is connected.
     */
    public record ConnectionBrief(String id, String connectorCode, String name,
                                  boolean enabled, String subCode, String authStatus) {
    }

    public record ConnectionList(List<ConnectionBrief> connections, boolean truncated) {
    }

    /** Deep link: the tool writes nothing to the database — a human creates the connection through the regular form, entering the secret outside the LLM. */
    public record ConnectionSetup(String status, String setupUrl, String connectorCode) {
    }

    // ---- C3-C4: instance inspection ----------------------------------------------------------

    public record ToolList(List<ToolBrief> tools, boolean truncated) {
    }

    public record ConnectionTestResult(boolean valid, String errorField, String errorMessage,
                                       boolean authorizationRequired) {
    }

    // ---- C5-C6: bindings --------------------------------------------------------------------

    public record AgentBindingList(List<AgentBinding> agents, boolean truncated) {
    }

    public record AgentBinding(String agentId, String agentName, boolean enabled) {
    }

    public record AgentConnectionList(List<AgentConnectionItem> connections, boolean truncated) {
    }

    public record AgentConnectionItem(String connectionId, String connectorCode, String name,
                                      boolean enabled, String authStatus, boolean managedBySkills) {
    }

    // ---- C8-C13: channels -------------------------------------------------------------------

    public record ChannelList(List<ChannelBrief> channels, boolean truncated) {
    }

    public record ChannelBrief(String id, String name, String channelHandler, String connectorCode,
                               String connectionId, String agentId, boolean active) {
    }

    public record ChannelDetail(String id, String name, String channelHandler, String connectorCode,
                                String connectionId, String agentId, boolean active,
                                Map<String, Object> config, Map<String, Object> inputFilter) {
    }

    public record ChannelHandlerList(List<ChannelHandlerInfo> handlers) {
    }

    public record ChannelHandlerInfo(String name, Map<String, Object> configFields) {
    }

    // ---- C14-C17: ABAC policies --------------------------------------------------------------

    public record PolicyList(List<PolicyDetail> policies, boolean truncated) {
    }

    public record PolicyDetail(String id, String kind, String name, String effect,
                               Map<String, Object> paramsFilter, String description) {
    }
}
