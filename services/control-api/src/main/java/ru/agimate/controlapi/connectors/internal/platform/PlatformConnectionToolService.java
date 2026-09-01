package ru.agimate.controlapi.connectors.internal.platform;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import ru.agimate.common.rest.error.BaseHttpStatusException;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.controlapi.abac.AccessEffect;
import ru.agimate.controlapi.abac.AgentConnectionPolicyService;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.connectors.core.ConnectorRegistry;
import ru.agimate.controlapi.connectors.core.TriggerProvider;
import ru.agimate.controlapi.connectors.core.annotation.Tool;
import ru.agimate.controlapi.connectors.core.annotation.ToolAnnotations;
import ru.agimate.controlapi.connectors.core.annotation.ToolParam;
import ru.agimate.controlapi.connectors.core.dto.ConnectorToolSpec;
import ru.agimate.controlapi.connectors.core.dto.IntegrationValidationResult;
import ru.agimate.controlapi.connectors.internal.platform.dto.PlatformConnectionDtos.AgentBinding;
import ru.agimate.controlapi.connectors.internal.platform.dto.PlatformConnectionDtos.AgentBindingList;
import ru.agimate.controlapi.connectors.internal.platform.dto.PlatformConnectionDtos.AgentConnectionItem;
import ru.agimate.controlapi.connectors.internal.platform.dto.PlatformConnectionDtos.AgentConnectionList;
import ru.agimate.controlapi.connectors.internal.platform.dto.PlatformConnectionDtos.ChannelBrief;
import ru.agimate.controlapi.connectors.internal.platform.dto.PlatformConnectionDtos.ChannelDetail;
import ru.agimate.controlapi.connectors.internal.platform.dto.PlatformConnectionDtos.ChannelHandlerInfo;
import ru.agimate.controlapi.connectors.internal.platform.dto.PlatformConnectionDtos.ChannelHandlerList;
import ru.agimate.controlapi.connectors.internal.platform.dto.PlatformConnectionDtos.ChannelList;
import ru.agimate.controlapi.connectors.internal.platform.dto.PlatformConnectionDtos.ConnectionBrief;
import ru.agimate.controlapi.connectors.internal.platform.dto.PlatformConnectionDtos.ConnectionList;
import ru.agimate.controlapi.connectors.internal.platform.dto.PlatformConnectionDtos.ConnectionSetup;
import ru.agimate.controlapi.connectors.internal.platform.dto.PlatformConnectionDtos.ConnectionTestResult;
import ru.agimate.controlapi.connectors.internal.platform.dto.PlatformConnectionDtos.ConnectorBrief;
import ru.agimate.controlapi.connectors.internal.platform.dto.PlatformConnectionDtos.ConnectorDetail;
import ru.agimate.controlapi.connectors.internal.platform.dto.PlatformConnectionDtos.ConnectorList;
import ru.agimate.controlapi.connectors.internal.platform.dto.PlatformConnectionDtos.PolicyDetail;
import ru.agimate.controlapi.connectors.internal.platform.dto.PlatformConnectionDtos.PolicyList;
import ru.agimate.controlapi.connectors.internal.platform.dto.PlatformConnectionDtos.ToolBrief;
import ru.agimate.controlapi.connectors.internal.platform.dto.PlatformConnectionDtos.ToolList;
import ru.agimate.controlapi.connectors.internal.platform.dto.PlatformConnectionDtos.TriggerBrief;
import ru.agimate.controlapi.connectors.internal.platform.dto.PlatformDtos.OperationResult;
import ru.agimate.controlapi.database.entities.AgentConnection;
import ru.agimate.controlapi.database.entities.AgentConnectionPolicy;
import ru.agimate.controlapi.database.entities.Channel;
import ru.agimate.controlapi.database.entities.Connection;
import ru.agimate.controlapi.database.entities.Connector;
import ru.agimate.controlapi.database.enums.PolicyKind;
import ru.agimate.controlapi.database.repositories.AgentConnectionRepository;
import ru.agimate.controlapi.database.repositories.ConnectionRepository;
import ru.agimate.controlapi.database.repositories.ConnectorRepository;
import ru.agimate.controlapi.service.channel.ChannelService;
import ru.agimate.controlapi.service.channel.ChannelService.CreateChannelData;
import ru.agimate.controlapi.service.channel.ChannelService.UpdateChannelData;
import ru.agimate.controlapi.service.connection.ConnectionBindingService;
import ru.agimate.controlapi.service.connection.ConnectionBindingService.AgentConnectionView;
import ru.agimate.controlapi.service.connection.ConnectionBindingService.ConnectionAgentView;
import ru.agimate.controlapi.service.connection.ConnectionService;
import ru.agimate.controlapi.service.tool.ToolDefinitionService;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tools of the platform connector's connections module — the meta-agent manages connectors and
 * connection instances on behalf of its human owner ({@code env.userId}). A thin adapter: reads come
 * from the repositories, writes go through the existing services (command overloads, so as not to
 * drag in {@code controller/**}). Domain {@link BaseHttpStatusException}s are translated into
 * {@link ConnectorException} so the message reaches the agent. Shared guards and parsing live in
 * {@link PlatformToolsSupport} (an agent does not manage itself — {@code requireNotSelf}).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlatformConnectionToolService {

    @Value("${app.frontend.base-url}")
    private String frontendBaseUrl;

    private final ConnectorRepository connectorRepository;
    private final ConnectionRepository connectionRepository;
    private final AgentConnectionRepository agentConnectionRepository;
    private final ConnectorRegistry connectorRegistry;
    private final ToolDefinitionService toolDefinitionService;
    private final ConnectionService connectionService;
    private final ConnectionBindingService connectionBindingService;
    private final AgentConnectionPolicyService agentConnectionPolicyService;
    private final ChannelService channelService;

    // ---- discovery -------------------------------------------------------------------------

    @Tool(name = "list_connectors",
            description = "List available connectors in the platform catalog. 'integration'=true means "
                    + "it needs a connection (credentials); integration=false connectors are attached "
                    + "to an agent via skills, not connections",
            annotations = @ToolAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = false))
    public ConnectorList listConnectors(
            @ToolParam(value = "Optional full-text search over connector name/description", required = false)
            String search) {
        String q = PlatformToolsSupport.blankToNull(search);
        List<ConnectorBrief> items = connectorRepository.search(q, PageRequest.of(0,
                        PlatformToolsSupport.MAX_LISTING, Sort.by("name").ascending()))
                .map(c -> new ConnectorBrief(c.getCode(), c.getName(), c.getDescription(), c.isIntegration()))
                .getContent();
        return new ConnectorList(items, items.size() == PlatformToolsSupport.MAX_LISTING);
    }

    @Tool(name = "get_connector",
            description = "Get a connector's details including the tools and triggers it provides — use "
                    + "this to understand a connector's capabilities before writing a skill for it",
            annotations = @ToolAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = false))
    public ConnectorDetail getConnector(
            @ToolParam("Connector code (e.g. telegram, board, persist-memory)") String code) {
        Connector connector = connectorRepository.findById(PlatformToolsSupport.requireText(code, "code"))
                .orElseThrow(() -> new ConnectorException("Connector not found: " + code));

        List<ToolBrief> tools = toolDefinitionService.getCatalogTools(connector.getCode()).values().stream()
                .map(PlatformConnectionToolService::toToolBrief)
                .toList();
        List<TriggerBrief> triggers = connectorRegistry.findCapability(connector.getCode(), TriggerProvider.class)
                .map(tp -> tp.getTriggers().entrySet().stream()
                        .map(e -> new TriggerBrief(e.getKey(), e.getValue().description()))
                        .toList())
                .orElseGet(List::of);

        return new ConnectorDetail(connector.getCode(), connector.getName(), connector.getDescription(),
                connector.isIntegration(), tools, triggers);
    }

    // ---- integrations ----------------------------------------------------------------------

    @Tool(name = "list_connections", description = "List your connector connections (integration instances)",
            annotations = @ToolAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = false))
    public ConnectionList listConnections(
            @ToolParam(value = "Filter by connector code", required = false) String connectorCode) {
        List<ConnectionBrief> items = connectionRepository
                .findByUserIdFiltered(PlatformToolsSupport.userId(),
                        PlatformToolsSupport.blankToNull(connectorCode), null).stream()
                .limit(PlatformToolsSupport.MAX_LISTING)
                .map(this::toConnectionBrief)
                .toList();
        return new ConnectionList(items, items.size() == PlatformToolsSupport.MAX_LISTING);
    }

    @Tool(name = "create_connection",
            description = "Start connecting an integration. Returns a setup link the user opens to enter "
                    + "credentials — secrets are never handled here. After the user finishes, call "
                    + "list_connections to get the new connection and bind_connection to attach it",
            annotations = @ToolAnnotations(destructiveHint = false, openWorldHint = false))
    public ConnectionSetup createConnection(
            @ToolParam("Integration connector code (e.g. telegram, mcp)") String connectorCode,
            @ToolParam(value = "Optional display name for the connection", required = false) String name) {
        String code = PlatformToolsSupport.requireText(connectorCode, "connectorCode");
        boolean integration = connectorRepository.findById(code).map(Connector::isIntegration).orElse(false);
        if (!integration) {
            throw new ConnectorException("Not an integration connector: " + code
                    + ". Only integration connectors need a connection; others attach via skills");
        }
        StringBuilder url = new StringBuilder(frontendBaseUrl)
                .append("/connections/new?connector=")
                .append(URLEncoder.encode(code, StandardCharsets.UTF_8));
        String displayName = PlatformToolsSupport.blankToNull(name);
        if (displayName != null) {
            url.append("&name=").append(URLEncoder.encode(displayName, StandardCharsets.UTF_8));
        }
        return new ConnectionSetup("setup_required", url.toString(), code);
    }

    @Tool(name = "bind_connection",
            description = "Bind an existing connection to an agent so the agent can use its tools. "
                    + "Only for integration connectors (create the connection first via create_connection)",
            annotations = @ToolAnnotations(destructiveHint = false, openWorldHint = false))
    public OperationResult bindConnection(
            @ToolParam("Agent public ID") String agentId,
            @ToolParam("Connection ID (from list_connections)") String connectionId) {
        UUID agent = PlatformToolsSupport.parseUuid(agentId, "agentId");
        PlatformToolsSupport.requireNotSelf(agent);
        UUID connection = PlatformToolsSupport.parseUuid(connectionId, "connectionId");
        PlatformToolsSupport.domain(() -> connectionBindingService.bindAndView(PlatformToolsSupport.userId(),
                agent, connection));
        return new OperationResult(true, "Connection bound to agent");
    }

    // ---- instance lifecycle ----------------------------------------------------------------

    @Tool(name = "update_connection",
            description = "Enable/disable a connection or rename it. Disabling stops its tools from "
                    + "being usable; an unauthorized (PENDING_AUTH) connection switched back on stays "
                    + "unusable until authorized. Omitted params are left unchanged; a blank name is also "
                    + "left unchanged (a connection always has a name)",
            annotations = @ToolAnnotations(destructiveHint = false, openWorldHint = false))
    public ConnectionBrief updateConnection(
            @ToolParam("Connection public ID") String connectionId,
            @ToolParam(value = "New display name", required = false) String name,
            @ToolParam(value = "Enable or disable the connection", required = false) Boolean enabled) {
        UUID id = PlatformToolsSupport.parseUuid(connectionId, "connectionId");
        // The service sets the name only when non-null — blankToNull maps "" → null → "keep" (PATCH 5.8).
        Connection updated = PlatformToolsSupport.domain(() -> connectionService.update(id,
                PlatformToolsSupport.userId(), enabled, PlatformToolsSupport.blankToNull(name)));
        return toConnectionBrief(updated);
    }

    @Tool(name = "delete_connection",
            description = "Delete a connection (integration instance) you own. The integration's webhook "
                    + "is removed and the connector's jobs/tools cache for this instance are cleaned. Agent "
                    + "bindings to it are NOT removed automatically — unbind them first (list_connection_agents)",
            annotations = @ToolAnnotations(destructiveHint = true, openWorldHint = false))
    public OperationResult deleteConnection(@ToolParam("Connection public ID") String connectionId) {
        UUID id = PlatformToolsSupport.parseUuid(connectionId, "connectionId");
        PlatformToolsSupport.domain(() -> {
            connectionService.delete(id, PlatformToolsSupport.userId());
            return null;
        });
        return new OperationResult(true, "Connection deleted");
    }

    @Tool(name = "test_connection",
            description = "Validate a connection: the platform checks reachability and credentials of the "
                    + "integration. Returns valid, an optional error field/message, and whether authorization "
                    + "is required",
            annotations = @ToolAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = true),
            timeoutSeconds = 120)
    public ConnectionTestResult testConnection(@ToolParam("Connection public ID") String connectionId) {
        UUID id = PlatformToolsSupport.parseUuid(connectionId, "connectionId");
        PlatformToolsSupport.domain(() -> connectionService.getOwnedConnection(id, PlatformToolsSupport.userId()));
        IntegrationValidationResult result = PlatformToolsSupport.domain(
                () -> connectionService.validate(id, PlatformToolsSupport.userId()));
        return new ConnectionTestResult(result.valid(), result.errorField(), result.errorMessage(),
                result.authorizationRequired());
    }

    @Tool(name = "list_connection_tools",
            description = "List the tools an owned connection instance exposes (for MCP — the discovered "
                    + "cache; for static connectors — their declared set), with input schemas. Use before "
                    + "writing ABAC policies",
            annotations = @ToolAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = false))
    public ToolList listConnectionTools(@ToolParam("Connection public ID") String connectionId) {
        Map<String, ConnectorToolSpec> tools = PlatformToolsSupport.domain(() -> toolDefinitionService
                .getConnectionTools(PlatformToolsSupport.userId(),
                        PlatformToolsSupport.parseUuid(connectionId, "connectionId")));
        List<ToolBrief> items = tools.values().stream()
                .map(PlatformConnectionToolService::toToolBrief)
                .toList();
        return new ToolList(items, items.size() == PlatformToolsSupport.MAX_LISTING);
    }

    // ---- bindings --------------------------------------------------------------------------

    @Tool(name = "list_connection_agents",
            description = "List the agents a connection is bound to (who uses this instance). Includes "
                    + "disabled agents — it is a usage inventory. Use before deleting a connection or changing "
                    + "its credentials",
            annotations = @ToolAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = false))
    public AgentBindingList listConnectionAgents(@ToolParam("Connection public ID") String connectionId) {
        List<ConnectionAgentView> views = PlatformToolsSupport.domain(() -> connectionBindingService
                .listForConnection(PlatformToolsSupport.userId(),
                        PlatformToolsSupport.parseUuid(connectionId, "connectionId")));
        List<AgentBinding> items = views.stream()
                .map(v -> new AgentBinding(v.agent().getId().toString(), v.agent().getName(),
                        v.agent().isEnabled()))
                .toList();
        return new AgentBindingList(items, items.size() == PlatformToolsSupport.MAX_LISTING);
    }

    @Tool(name = "list_agent_connections",
            description = "List the connector connections bound to an agent (the availability gate): "
                    + "external instances by id, internal mode rows by code. managedBySkills=true means the "
                    + "binding came from a skill. Use with list_agent_skills to find missing connections",
            annotations = @ToolAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = false))
    public AgentConnectionList listAgentConnections(@ToolParam("Agent public ID") String agentId) {
        UUID agent = PlatformToolsSupport.parseUuid(agentId, "agentId");
        // Read-only listing — no self-guard (see list_agent_skills); owner scope stays.
        List<AgentConnectionView> views = PlatformToolsSupport.domain(() -> connectionBindingService
                .listForAgent(PlatformToolsSupport.userId(), agent));
        List<AgentConnectionItem> items = views.stream()
                .map(v -> {
                    Connection c = v.connection();
                    return new AgentConnectionItem(c.getId().toString(), c.getConnectorCode(), c.getName(),
                            Boolean.TRUE.equals(c.getEnabled()), c.getAuthStatus().name(), v.managedBySkills());
                })
                .toList();
        return new AgentConnectionList(items, items.size() == PlatformToolsSupport.MAX_LISTING);
    }

    @Tool(name = "unbind_connection",
            description = "Close a connector for an agent: the binding and its access policies are "
                    + "removed. Skills that pointed at this instance go unsatisfied. Cannot unbind your own "
                    + "bindings — the calling agent cannot manage itself",
            annotations = @ToolAnnotations(destructiveHint = true, openWorldHint = false))
    public OperationResult unbindConnection(
            @ToolParam("Agent public ID") String agentId,
            @ToolParam("Connection public ID (from list_agent_connections)") String connectionId) {
        UUID agent = PlatformToolsSupport.parseUuid(agentId, "agentId");
        PlatformToolsSupport.requireNotSelf(agent);
        UUID connection = PlatformToolsSupport.parseUuid(connectionId, "connectionId");
        PlatformToolsSupport.domain(() -> {
            connectionBindingService.unbind(PlatformToolsSupport.userId(), agent, connection);
            return null;
        });
        return new OperationResult(true, "Connection unbound from agent");
    }

    // ---- channels --------------------------------------------------------------------------

    @Tool(name = "list_channels",
            description = "List the channels (dialogue endpoints: telegram, webchat, ...) of your "
                    + "account, optionally filtered by the agent a channel talks to",
            annotations = @ToolAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = false))
    public ChannelList listChannels(
            @ToolParam(value = "Filter by agent public ID", required = false) String agentId) {
        UUID agent = PlatformToolsSupport.parseUuidOrNull(agentId, "agentId");
        List<Channel> channels = agent == null
                ? channelService.listForUser(PlatformToolsSupport.userId())
                : channelService.listForUserAndAgent(PlatformToolsSupport.userId(), agent);
        List<ChannelBrief> briefs = channels.stream()
                .limit(PlatformToolsSupport.MAX_LISTING)
                .map(this::toChannelBrief)
                .toList();
        return new ChannelList(briefs, briefs.size() == PlatformToolsSupport.MAX_LISTING);
    }

    @Tool(name = "get_channel",
            description = "Get a channel's full configuration: handler, source connector/connection, "
                    + "handler config and the inbound chat filter",
            annotations = @ToolAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = false))
    public ChannelDetail getChannel(@ToolParam("Channel public ID") String id) {
        Channel channel = PlatformToolsSupport.domain(() -> channelService.getById(
                PlatformToolsSupport.userId(), PlatformToolsSupport.parseUuid(id, "id")));
        return toChannelDetail(channel);
    }

    @Tool(name = "create_channel",
            description = "Create a channel: a dialogue endpoint (handler code from "
                    + "list_channel_handlers, e.g. telegram, webchat) between an agent and a user, built "
                    + "on a connector connection. The agent must be push-capable (GENERIC/CENTRIFUGO); "
                    + "the channel binds the source and reply connections to the agent as a side effect",
            annotations = @ToolAnnotations(destructiveHint = false, openWorldHint = false))
    public ChannelDetail createChannel(
            @ToolParam("Agent public ID the channel talks to") String agentId,
            @ToolParam("Channel name") String name,
            @ToolParam("Channel handler code — from list_channel_handlers (e.g. telegram, webchat)") String channelHandler,
            @ToolParam("Connection public ID of the instance (from list_connections / list_agent_connections)") String connectionId,
            @ToolParam(value = "Handler-specific config (schema from list_channel_handlers)", required = false) Map<String, Object> config,
            @ToolParam(value = "Chat filter applied to inbound messages; empty map = no filter", required = false) Map<String, Object> inputFilter) {
        UUID agent = PlatformToolsSupport.parseUuid(agentId, "agentId");
        PlatformToolsSupport.requireNotSelf(agent);
        Channel channel = PlatformToolsSupport.domain(() -> {
            // The connection knows its own connector code — the model must not guess a value the
            // service would only echo back (the REST DTO kept both as belt-and-braces; a tool
            // surface should not force an extra lookup for a derivable value). The lookup is
            // owner-scoped: a foreign id must read as not found, not hand out its connector code.
            UUID connection = PlatformToolsSupport.parseUuid(connectionId, "connectionId");
            String connectorCode = connectionRepository
                    .findByIdAndUserIdNotDeleted(connection, PlatformToolsSupport.userId())
                    .orElseThrow(() -> new ConnectorException("Connection not found: " + connection))
                    .getConnectorCode();
            return channelService.create(PlatformToolsSupport.userId(),
                    new CreateChannelData(agent, PlatformToolsSupport.requireText(name, "name"),
                            PlatformToolsSupport.requireText(channelHandler, "channelHandler"),
                            connectorCode, connection.toString(), config, inputFilter));
        });
        return toChannelDetail(channel);
    }

    @Tool(name = "update_channel",
            description = "Update a channel: name, config or the inbound chat filter. PATCH semantics: "
                    + "omitted params are kept, an empty config/filter map clears the field. Changing the "
                    + "trigger/tool set is refused — recreate the channel instead. Cannot update a channel "
                    + "whose subject agent is the calling agent",
            annotations = @ToolAnnotations(destructiveHint = false, openWorldHint = false))
    public ChannelDetail updateChannel(
            @ToolParam("Channel public ID") String id,
            @ToolParam(value = "New name", required = false) String name,
            @ToolParam(value = "New config; empty map clears; changing the trigger/tool set is refused (recreate instead)",
                    required = false) Map<String, Object> config,
            @ToolParam(value = "New chat filter; empty map clears", required = false) Map<String, Object> inputFilter) {
        UUID channelId = PlatformToolsSupport.parseUuid(id, "id");
        // A channel always has a name — a blank one is an error, not a "clear" (the /manage canon for
        // names that cannot be cleared, same as update_agent/update_team).
        if (name != null && name.isBlank()) {
            throw new ConnectorException("Channel name must not be blank");
        }
        // Self-guard on the channel's subject agent (5.2) — the load doubles as the PATCH "load current
        // record first" step (5.8). The service itself resolves name/config/inputFilter PATCH semantics;
        // the empty-map→clear of inputFilter is passed through the clearInputFilter flag.
        Channel existing = PlatformToolsSupport.domain(() -> channelService.getById(
                PlatformToolsSupport.userId(), channelId));
        PlatformToolsSupport.requireNotSelf(existing.getAgentId());
        boolean clearInputFilter = inputFilter != null && inputFilter.isEmpty();
        Channel channel = PlatformToolsSupport.domain(() -> channelService.update(PlatformToolsSupport.userId(),
                channelId, new UpdateChannelData(name, config, inputFilter, clearInputFilter)));
        return toChannelDetail(channel);
    }

    @Tool(name = "delete_channel",
            description = "Delete a channel (soft delete). The agent's bindings to the source connector "
                    + "are preserved — revoke them explicitly with unbind_connection. Cannot delete a "
                    + "channel whose subject agent is the calling agent",
            annotations = @ToolAnnotations(destructiveHint = true, openWorldHint = false))
    public OperationResult deleteChannel(@ToolParam("Channel public ID") String id) {
        UUID channelId = PlatformToolsSupport.parseUuid(id, "id");
        Channel channel = PlatformToolsSupport.domain(() -> channelService.getById(
                PlatformToolsSupport.userId(), channelId));
        PlatformToolsSupport.requireNotSelf(channel.getAgentId());
        PlatformToolsSupport.domain(() -> {
            channelService.delete(PlatformToolsSupport.userId(), channelId);
            return null;
        });
        return new OperationResult(true, "Channel deleted");
    }

    @Tool(name = "list_channel_handlers",
            description = "List the channel handlers the platform supports, with the config fields each "
                    + "accepts. Use before create_channel",
            annotations = @ToolAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = false))
    public ChannelHandlerList listChannelHandlers() {
        List<ChannelHandlerInfo> items = channelService.listHandlersFlat().stream()
                .map(h -> new ChannelHandlerInfo(h.name(), h.configFields()))
                .toList();
        return new ChannelHandlerList(items);
    }

    // ---- ABAC policies ---------------------------------------------------------------------

    @Tool(name = "list_policies",
            description = "List the ABAC access policies (TOOL/TRIGGER allow-deny rules) of an "
                    + "agent-connection binding, with the params filter of each",
            annotations = @ToolAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = false))
    public PolicyList listPolicies(
            @ToolParam("Agent-connection binding public ID (from list_agent_connections)") String agentConnectionId) {
        List<AgentConnectionPolicy> policies = PlatformToolsSupport.domain(() -> agentConnectionPolicyService
                .getPolicies(PlatformToolsSupport.userId(),
                        PlatformToolsSupport.parseUuid(agentConnectionId, "agentConnectionId")));
        List<PolicyDetail> policyItems = policies.stream()
                .limit(PlatformToolsSupport.MAX_LISTING)
                .map(this::toPolicyDetail)
                .toList();
        return new PolicyList(policyItems, policyItems.size() == PlatformToolsSupport.MAX_LISTING);
    }

    @Tool(name = "create_policy",
            description = "Add an ABAC access policy to an agent-connection binding: an ALLOW or DENY "
                    + "rule for one tool/trigger (or the whole binding when name is omitted). Cannot "
                    + "create policies about the agent that is calling",
            annotations = @ToolAnnotations(destructiveHint = false, openWorldHint = false))
    public PolicyDetail createPolicy(
            @ToolParam("Agent-connection binding public ID") String agentConnectionId,
            @ToolParam("Rule kind: TOOL or TRIGGER") String kind,
            @ToolParam(value = "Tool/trigger name the rule addresses; null/empty = binding-wide", required = false) String name,
            @ToolParam("Effect: ALLOW or DENY") String effect,
            @ToolParam(value = "Params filter (JSON object); empty object = no filter", required = false) Map<String, Object> paramsFilter,
            @ToolParam(value = "Free-form description", required = false) String description) {
        AgentConnection binding = requireOwnedBinding(agentConnectionId);
        PlatformToolsSupport.requireNotSelf(binding.getAgentId());
        AgentConnectionPolicy policy = PlatformToolsSupport.domain(() -> agentConnectionPolicyService.create(
                PlatformToolsSupport.userId(), binding.getId(),
                PlatformToolsSupport.parseEnum(PolicyKind.class, kind, "kind"),
                PlatformToolsSupport.blankToNull(name),
                PlatformToolsSupport.parseEnum(AccessEffect.class, effect, "effect"),
                paramsFilter, PlatformToolsSupport.blankToNull(description)));
        return toPolicyDetail(policy);
    }

    @Tool(name = "update_policy",
            description = "Update an ABAC policy: effect, params filter or description. PATCH semantics: "
                    + "omitted params are kept, an empty params filter clears it, an empty description "
                    + "clears it. Cannot update policies about the agent that is calling",
            annotations = @ToolAnnotations(destructiveHint = false, openWorldHint = false))
    public PolicyDetail updatePolicy(
            @ToolParam("Agent-connection binding public ID") String agentConnectionId,
            @ToolParam("Policy public ID") String policyId,
            @ToolParam(value = "New effect: ALLOW or DENY", required = false) String effect,
            @ToolParam(value = "New params filter; empty object clears, omitted keeps", required = false) Map<String, Object> paramsFilter,
            @ToolParam(value = "New description; empty string clears, omitted keeps", required = false) String description) {
        AgentConnection binding = requireOwnedBinding(agentConnectionId);
        PlatformToolsSupport.requireNotSelf(binding.getAgentId());
        UUID policyIdUuid = PlatformToolsSupport.parseUuid(policyId, "policyId");
        AgentConnectionPolicy current = PlatformToolsSupport.domain(() -> agentConnectionPolicyService
                .getPolicyById(PlatformToolsSupport.userId(), policyIdUuid));
        // PATCH resolution (5.8): the service sets paramsFilter unconditionally, so "absent" MUST be
        // resolved to the current value here; an empty map means clear (→ null). A blank effect is
        // "not sent" too — there is no "clear" for an enum, and parseEnum would NPE on a blank value.
        Map<String, Object> resolvedFilter = paramsFilter == null ? current.getParamsFilter()
                : (paramsFilter.isEmpty() ? null : paramsFilter);
        AccessEffect resolvedEffect = effect == null || effect.isBlank() ? current.getEffect()
                : PlatformToolsSupport.parseEnum(AccessEffect.class, effect, "effect");
        // Raw passthrough: the service resolves null=keep, ""=clear (the /manage PATCH convention).
        String resolvedDescription = description;
        AgentConnectionPolicy updated = PlatformToolsSupport.domain(() -> agentConnectionPolicyService.update(
                PlatformToolsSupport.userId(), binding.getId(), policyIdUuid, resolvedEffect, resolvedFilter,
                resolvedDescription));
        return toPolicyDetail(updated);
    }

    @Tool(name = "delete_policy",
            description = "Delete an ABAC access policy of an agent-connection binding. Cannot delete "
                    + "policies about the agent that is calling",
            annotations = @ToolAnnotations(destructiveHint = true, openWorldHint = false))
    public OperationResult deletePolicy(
            @ToolParam("Agent-connection binding public ID") String agentConnectionId,
            @ToolParam("Policy public ID") String policyId) {
        AgentConnection binding = requireOwnedBinding(agentConnectionId);
        PlatformToolsSupport.requireNotSelf(binding.getAgentId());
        UUID policyIdUuid = PlatformToolsSupport.parseUuid(policyId, "policyId");
        PlatformToolsSupport.domain(() -> {
            agentConnectionPolicyService.delete(PlatformToolsSupport.userId(), binding.getId(), policyIdUuid);
            return null;
        });
        return new OperationResult(true, "Policy deleted");
    }

    // ---- helpers ---------------------------------------------------------------------------


    /** The connector's tool view: name, description and the input schema (for writing params_filter). */
    private static ToolBrief toToolBrief(ConnectorToolSpec spec) {
        Map<String, Object> schema = spec.inputSchema() == null ? null
                : JsonUtils.MAPPER.convertValue(spec.inputSchema(), Map.class);
        return new ToolBrief(spec.name(), spec.description(), schema);
    }

    private ConnectionBrief toConnectionBrief(Connection connection) {
        return new ConnectionBrief(connection.getId().toString(), connection.getConnectorCode(),
                connection.getName(), Boolean.TRUE.equals(connection.getEnabled()), connection.getSubCode(),
                connection.getAuthStatus().name());
    }

    private ChannelBrief toChannelBrief(Channel channel) {
        return new ChannelBrief(channel.getId().toString(), channel.getName(), channel.getChannelHandler(),
                channel.getConnectorCode(), channel.getConnectionId().toString(),
                channel.getAgentId().toString(), channel.isActive());
    }

    private ChannelDetail toChannelDetail(Channel channel) {
        return new ChannelDetail(channel.getId().toString(), channel.getName(), channel.getChannelHandler(),
                channel.getConnectorCode(), channel.getConnectionId().toString(),
                channel.getAgentId().toString(), channel.isActive(), channel.getConfig(),
                channel.getInputFilter());
    }

    private PolicyDetail toPolicyDetail(AgentConnectionPolicy policy) {
        return new PolicyDetail(policy.getId().toString(), policy.getKind().name(), policy.getName(),
                policy.getEffect().name(), policy.getParamsFilter(), policy.getDescription());
    }

    /**
     * The caller's own active binding — mirrors {@code AgentConnectionPolicyService.ownedBinding}
     * (the binding's connection must be the caller's, else "Binding not found"), so the subject-agent
     * self-guard of the policy mutations (5.2, BLOCKER-2) can be applied before the service call.
     */
    private AgentConnection requireOwnedBinding(String agentConnectionId) {
        UUID bindingId = PlatformToolsSupport.parseUuid(agentConnectionId, "agentConnectionId");
        AgentConnection binding = agentConnectionRepository.findById(bindingId)
                .filter(AgentConnection::isActive)
                .orElseThrow(() -> new ConnectorException("Binding not found"));
        Connection connection = connectionRepository.findByIdNotDeleted(binding.getConnectionId())
                .orElseThrow(() -> new ConnectorException("Connection not found"));
        if (!connection.getUserId().equals(PlatformToolsSupport.userId())) {
            throw new ConnectorException("Binding not found");
        }
        return binding;
    }
}
