package ru.agimate.controlapi.service.channel;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.ConflictStatusException;
import ru.agimate.common.rest.error.ForbiddenStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.connectors.core.ConnectorRegistry;
import ru.agimate.controlapi.connectors.core.ToolProvider;
import ru.agimate.controlapi.connectors.core.TriggerProvider;
import ru.agimate.controlapi.controller.manage.dto.channel.ChannelHandlerResponse;
import ru.agimate.controlapi.controller.manage.dto.channel.ChannelResponse;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.entities.Channel;
import ru.agimate.controlapi.database.entities.Connection;
import ru.agimate.controlapi.database.entities.ConnectionTool;
import ru.agimate.controlapi.database.entities.ConnectionTrigger;
import ru.agimate.controlapi.database.entities.Connector;
import ru.agimate.controlapi.database.repositories.AgentRepository;
import ru.agimate.controlapi.service.AgentDeliveryService;
import ru.agimate.controlapi.database.repositories.ChannelRepository;
import ru.agimate.controlapi.database.repositories.ConnectionRepository;
import ru.agimate.controlapi.database.repositories.ConnectionToolRepository;
import ru.agimate.controlapi.database.repositories.ConnectionTriggerRepository;
import ru.agimate.controlapi.database.repositories.ConnectorRepository;
import ru.agimate.controlapi.service.channel.handler.dto.ChannelConfig;
import ru.agimate.controlapi.service.channel.handler.ChannelHandler;
import ru.agimate.controlapi.service.channel.handler.ChannelHandlerRegistry;
import ru.agimate.controlapi.service.channel.handler.dto.ToolDefinition;
import ru.agimate.controlapi.service.channel.handler.dto.TriggerDefinition;
import ru.agimate.controlapi.service.connection.ConnectionBindingService;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChannelService {

    private final ChannelRepository channelRepository;
    private final AgentRepository agentRepository;
    private final ConnectorRepository connectorRepository;
    private final ConnectionRepository connectionRepository;
    private final ConnectionToolRepository connectionToolRepository;
    private final ConnectionTriggerRepository connectionTriggerRepository;
    private final ConnectorRegistry connectorRegistry;
    private final ChannelHandlerRegistry channelHandlerRegistry;
    private final ConnectionBindingService connectionBindingService;
    private final AgentDeliveryService agentDeliveryService;

    public Channel getById(UUID userId, UUID id) {
        Channel channel = channelRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new NotFoundStatusException("Channel not found"));
        if (!channel.getUserId().equals(userId)) {
            throw new ForbiddenStatusException("Access denied");
        }
        return channel;
    }

    public Channel getByIdForUser(UUID userId, UUID id) {
        Channel channel = channelRepository.findById(id)
                .orElseThrow(() -> new NotFoundStatusException("Channel not found"));
        if (!channel.getUserId().equals(userId)) {
            throw new ForbiddenStatusException("Access denied");
        }
        return channel;
    }

    public List<Channel> listForUser(UUID userId) {
        return channelRepository.findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(userId);
    }

    public List<Channel> listForUserAndAgent(UUID userId, UUID agentId) {
        return channelRepository.findByUserIdAndAgentIdAndDeletedAtIsNullOrderByCreatedAtDesc(userId, agentId);
    }

    public List<ChannelHandlerResponse> listHandlers() {
        return channelHandlerRegistry.all().stream()
                .map(h -> new ChannelHandlerResponse(h.name(), h.getConfigFields()))
                .toList();
    }

    public ChannelResponse toResponse(Channel channel) {
        return toResponses(List.of(channel)).get(0);
    }

    public List<ChannelResponse> toResponses(List<Channel> channels) {
        if (channels.isEmpty()) {
            return List.of();
        }
        Map<UUID, String> nameById = resolveConnectionNames(channels);
        Map<UUID, Map<String, Object>> inputFilterByChannelId = resolveInputFilters(channels);
        return channels.stream()
                .map(c -> ChannelResponse.from(
                        c,
                        nameById.get(c.getConnectionId()),
                        inputFilterByChannelId.get(c.getId())))
                .toList();
    }

    private Map<UUID, Map<String, Object>> resolveInputFilters(List<Channel> channels) {
        Map<UUID, Map<String, Object>> result = new HashMap<>();
        for (Channel c : channels) {
            if (c.getInputFilter() != null) {
                result.put(c.getId(), c.getInputFilter());
            }
        }
        return result;
    }

    private Map<UUID, String> resolveConnectionNames(List<Channel> channels) {
        Set<UUID> connectionIds = new HashSet<>();
        for (Channel c : channels) {
            UUID id = c.getConnectionId();
            if (id != null) connectionIds.add(id);
        }
        if (connectionIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> result = new HashMap<>();
        for (UUID id : connectionIds) {
            connectionRepository.findByIdNotDeleted(id).ifPresent(c -> {
                String name = c.getName() != null && !c.getName().isBlank() ? c.getName() : c.getSubCode();
                result.put(c.getId(), name);
            });
        }
        return result;
    }

    private static UUID tryParseUuid(String value) {
        if (value == null) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Transactional
    public Channel create(UUID userId, CreateChannelData data) {
        Agent agent = agentRepository.findById(data.agentId())
                .orElseThrow(() -> new NotFoundStatusException("Agent not found"));
        if (!agent.getUserId().equals(userId)) {
            throw new ForbiddenStatusException("Access denied to agent");
        }
        // A channel is a two-way conversation, and the inbound half needs a push transport. Without one
        // the messages would pile up in channel_sessions with nobody ever woken to read them.
        if (!agentDeliveryService.supportsPush(agent)) {
            throw new BadRequestStatusException(
                    "Agent of type " + agent.getType() + " receives no messages, so it cannot have channels");
        }

        ChannelHandler handler = requireHandler(data.channelHandler());
        Map<String, Object> config = data.config() != null ? data.config() : Map.of();
        ChannelConfig channelConfig = new ChannelConfig(data.agentId(), data.connectorCode(), data.connectionId(), config);
        validateConfig(handler, channelConfig);

        List<TriggerDefinition> triggers = handler.listOfTriggers(channelConfig);
        List<ToolDefinition> tools = handler.listOfTools(channelConfig);

        // Validate that the source connector and every trigger/tool actually exist for this user.
        for (TriggerDefinition t : triggers) {
            validateTrigger(userId, data.connectorCode(), data.connectionId(), t.triggerName());
        }
        for (ToolDefinition t : tools) {
            validateTool(userId, t.connectionId(), t.toolName());
        }

        UUID connectionId = parseUuid(data.connectionId(), "connectionId");
        if (channelRepository.findByAgentIdAndConnectorCodeAndConnectionIdAndDeletedAtIsNull(
                data.agentId(), data.connectorCode(), connectionId).isPresent()) {
            throw new ConflictStatusException(
                    "Active channel already exists for this agent/connector/connectionId");
        }

        Channel channel = channelRepository.save(Channel.builder()
                .userId(userId)
                .agentId(data.agentId())
                .name(data.name())
                .channelHandler(handler.name())
                .connectorCode(data.connectorCode())
                .connectionId(connectionId)
                .inputFilter(data.inputFilter())
                .config(config)
                .build());

        // Access = a binding: a channel guarantees a binding onto its own source and onto every reply
        // connection (default-allow replaces the former automatic issuing of ALLOW policies). Bindings outlive
        // the channel.
        Set<UUID> toBind = new LinkedHashSet<>();
        toBind.add(connectionId);
        for (ToolDefinition t : tools) {
            UUID replyConnection = tryParseUuid(t.connectionId());
            if (replyConnection != null) {
                toBind.add(replyConnection);
            }
        }
        for (UUID cid : toBind) {
            connectionBindingService.ensureBindingToExisting(userId, data.agentId(), cid);
        }

        log.info("Created channel id={} handler={} for agent={} user={} (bound {} connection(s))",
                channel.getId(), handler.name(), data.agentId(), userId, toBind.size());
        return channel;
    }

    @Transactional
    public Channel update(UUID userId, UUID id, UpdateChannelData data) {
        Channel channel = getById(userId, id);

        if (data.name() != null) {
            channel.setName(data.name());
        }

        if (data.config() != null) {
            ChannelHandler handler = requireHandler(channel.getChannelHandler());
            ChannelConfig oldCfg = new ChannelConfig(channel.getAgentId(), channel.getConnectorCode(), channel.getConnectionId().toString(), channel.getConfig());
            ChannelConfig newCfg = new ChannelConfig(channel.getAgentId(), channel.getConnectorCode(), channel.getConnectionId().toString(), data.config());
            validateConfig(handler, newCfg);
            // Trigger/tool set is fixed at creation — changing it requires recreating the channel,
            // otherwise the generated policies would drift out of sync.
            if (!triggerKeys(handler, oldCfg).equals(triggerKeys(handler, newCfg))
                    || !toolKeys(handler, oldCfg).equals(toolKeys(handler, newCfg))) {
                throw new BadRequestStatusException(
                        "Changing the trigger/tool set is not allowed; recreate the channel instead");
            }
            channel.setConfig(data.config());
        }

        if (data.inputFilter() != null || data.clearInputFilter()) {
            channel.setInputFilter(data.clearInputFilter() ? null : data.inputFilter());
        }

        return channelRepository.save(channel);
    }

    @Transactional
    public void delete(UUID userId, UUID id) {
        Channel channel = getById(userId, id);
        channel.setDeletedAt(LocalDateTime.now());
        channelRepository.save(channel);
        // Bindings are left alone — their lifecycle is separate (decision #1): the agent's access to the
        // connector survives the channel's deletion and is revoked by an explicit unbind.
        log.info("Soft-deleted channel id={} (bindings preserved)", id);
    }

    private ChannelHandler requireHandler(String name) {
        if (name == null || name.isBlank()) {
            throw new BadRequestStatusException("channelHandler is required");
        }
        return channelHandlerRegistry.find(name)
                .orElseThrow(() -> new BadRequestStatusException("Unknown channel handler: " + name));
    }

    private void validateConfig(ChannelHandler handler, ChannelConfig config) {
        try {
            handler.validateConfig(config);
        } catch (ConnectorException e) {
            throw new BadRequestStatusException(e.getMessage());
        }
    }

    private Set<String> triggerKeys(ChannelHandler handler, ChannelConfig config) {
        return handler.listOfTriggers(config).stream()
                .map(TriggerDefinition::triggerName)
                .collect(Collectors.toSet());
    }

    private Set<String> toolKeys(ChannelHandler handler, ChannelConfig config) {
        return handler.listOfTools(config).stream()
                .map(t -> t.connectionId() + "|" + t.toolName())
                .collect(Collectors.toSet());
    }

    private void validateTrigger(UUID userId, String connectorCode, String connectionId, String triggerName) {
        Connector connector = connectorRepository.findById(connectorCode)
                .orElseThrow(() -> new NotFoundStatusException("Connector not found: " + connectorCode));
        Set<String> available = lookupTriggerNames(connector, userId, connectionId);
        if (!available.contains(triggerName)) {
            throw new BadRequestStatusException(
                    "Trigger '" + triggerName + "' not available on connector '" + connectorCode + "'");
        }
    }

    private void validateTool(UUID userId, String connectionId, String toolName) {
        // The reply connector is derived from the connection (connections.connector_code) — it is not stored in the config.
        Connection connection = loadConnection(userId, connectionId);
        String connectorCode = connection.getConnectorCode();
        Connector connector = connectorRepository.findById(connectorCode)
                .orElseThrow(() -> new NotFoundStatusException("Reply connector not found: " + connectorCode));
        Set<String> available = lookupToolNames(connector, userId, connectionId);
        if (!available.contains(toolName)) {
            throw new BadRequestStatusException(
                    "Tool '" + toolName + "' not available on connector '" + connectorCode + "'");
        }
    }

    /** Source by definitionBinding: STATIC — from the handler (SPI), DYNAMIC — from connection_triggers. */
    private Set<String> lookupTriggerNames(Connector connector, UUID userId, String connectionId) {
        Connection connection = loadConnection(userId, connector.getCode(), connectionId);
        return switch (connector.getDefinitionBinding()) {
            case STATIC -> connectorRegistry.findCapability(connector.getCode(), TriggerProvider.class)
                    .map(provider -> provider.getTriggers().keySet()).orElse(Set.of());
            case DYNAMIC -> connectionTriggerRepository.findActiveByConnectionId(connection.getId()).stream()
                    .map(ConnectionTrigger::getName).collect(Collectors.toSet());
            case null -> Set.of();
        };
    }

    /** Source by definitionBinding: STATIC — from the handler (SPI), DYNAMIC — from connection_tools. */
    private Set<String> lookupToolNames(Connector connector, UUID userId, String connectionId) {
        Connection connection = loadConnection(userId, connector.getCode(), connectionId);
        return switch (connector.getDefinitionBinding()) {
            case STATIC -> connectorRegistry.findCapability(connector.getCode(), ToolProvider.class)
                    .map(provider -> provider.getTools().keySet()).orElse(Set.of());
            case DYNAMIC -> connectionToolRepository.findActiveByConnectionId(connection.getId()).stream()
                    .map(ConnectionTool::getName).collect(Collectors.toSet());
            case null -> Set.of();
        };
    }

    private Connection loadConnection(UUID userId, String connectionId) {
        UUID id = parseUuid(connectionId, "connectionId");
        Connection connection = connectionRepository.findByIdAndUserIdNotDeleted(id, userId)
                .orElseThrow(() -> new NotFoundStatusException("Connection not found: " + connectionId));
        if (!connection.isUsable()) {
            throw new BadRequestStatusException("Connection is not active: " + connectionId);
        }
        return connection;
    }

    private Connection loadConnection(UUID userId, String connectorCode, String connectionId) {
        Connection connection = loadConnection(userId, connectionId);
        if (!connection.getConnectorCode().equals(connectorCode)) {
            throw new BadRequestStatusException(
                    "Connector mismatch: expected " + connectorCode + " got " + connection.getConnectorCode());
        }
        return connection;
    }

    private UUID parseUuid(String value, String fieldName) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new BadRequestStatusException("Invalid " + fieldName + ": " + value);
        }
    }

    public record CreateChannelData(
            UUID agentId,
            String name,
            String channelHandler,
            String connectorCode,
            String connectionId,
            Map<String, Object> config,
            Map<String, Object> inputFilter
    ) {}

    public record UpdateChannelData(
            String name,
            Map<String, Object> config,
            Map<String, Object> inputFilter,
            boolean clearInputFilter
    ) {}
}
