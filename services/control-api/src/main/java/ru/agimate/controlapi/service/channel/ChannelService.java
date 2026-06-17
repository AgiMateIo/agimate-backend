package ru.agimate.controlapi.service.channel;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.ConflictStatusException;
import ru.agimate.common.rest.error.ForbiddenStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.controlapi.abac.AccessEffect;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.connectors.core.ConnectorHandler;
import ru.agimate.controlapi.connectors.core.ConnectorRegistry;
import ru.agimate.controlapi.controller.manage.dto.channel.ChannelHandlerResponse;
import ru.agimate.controlapi.controller.manage.dto.channel.ChannelResponse;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.entities.AgentToolPolicy;
import ru.agimate.controlapi.database.entities.AgentTriggerPolicy;
import ru.agimate.controlapi.database.entities.App;
import ru.agimate.controlapi.database.entities.Channel;
import ru.agimate.controlapi.database.entities.Connector;
import ru.agimate.controlapi.database.entities.IntegrationCredentials;
import ru.agimate.controlapi.database.repositories.AgentRepository;
import ru.agimate.controlapi.database.repositories.AgentToolPolicyRepository;
import ru.agimate.controlapi.database.repositories.AgentTriggerPolicyRepository;
import ru.agimate.controlapi.database.repositories.AppRepository;
import ru.agimate.controlapi.database.repositories.ChannelRepository;
import ru.agimate.controlapi.database.repositories.ConnectorRepository;
import ru.agimate.controlapi.database.repositories.IntegrationCredentialsRepository;
import ru.agimate.controlapi.service.channel.handler.dto.ChannelConfig;
import ru.agimate.controlapi.service.channel.handler.ChannelHandler;
import ru.agimate.controlapi.service.channel.handler.ChannelHandlerRegistry;
import ru.agimate.controlapi.service.channel.handler.dto.ToolDefinition;
import ru.agimate.controlapi.service.channel.handler.dto.TriggerDefinition;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
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
    private final AppRepository appRepository;
    private final IntegrationCredentialsRepository integrationCredentialsRepository;
    private final ConnectorRegistry connectorRegistry;
    private final ChannelHandlerRegistry channelHandlerRegistry;
    private final AgentTriggerPolicyRepository agentTriggerPolicyRepository;
    private final AgentToolPolicyRepository agentToolPolicyRepository;

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

    public List<Channel> listForAgent(UUID agentId) {
        return channelRepository.findByAgentIdAndDeletedAtIsNullOrderByCreatedAtDesc(agentId);
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
        Map<UUID, String> nameById = resolveIdentityNames(channels);
        Map<UUID, Map<String, Object>> inputFilterByChannelId = resolveInputFilters(channels);
        return channels.stream()
                .map(c -> ChannelResponse.from(
                        c,
                        nameById.get(tryParseUuid(c.getIdentity())),
                        inputFilterByChannelId.get(c.getId())))
                .toList();
    }

    private Map<UUID, Map<String, Object>> resolveInputFilters(List<Channel> channels) {
        List<UUID> ids = channels.stream().map(Channel::getId).toList();
        Map<UUID, Map<String, Object>> result = new HashMap<>();
        for (AgentTriggerPolicy p : agentTriggerPolicyRepository.findByChannelIdIn(ids)) {
            if (p.getChannelId() != null && p.getInputFilter() != null) {
                result.put(p.getChannelId(), p.getInputFilter());
            }
        }
        return result;
    }

    private Map<UUID, String> resolveIdentityNames(List<Channel> channels) {
        Set<UUID> identityIds = new HashSet<>();
        for (Channel c : channels) {
            UUID id = tryParseUuid(c.getIdentity());
            if (id != null) identityIds.add(id);
        }
        if (identityIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> result = new HashMap<>();
        for (App app : appRepository.findAllByIdInNotDeleted(identityIds)) {
            result.put(app.getId(), app.getName());
        }
        for (IntegrationCredentials i : integrationCredentialsRepository.findAllByIdInNotDeleted(identityIds)) {
            String name = i.getName() != null && !i.getName().isBlank()
                    ? i.getName() : i.getPlatformIdentifier();
            result.put(i.getId(), name);
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

        ChannelHandler handler = requireHandler(data.channelHandler());
        Map<String, Object> config = data.config() != null ? data.config() : Map.of();
        ChannelConfig channelConfig = new ChannelConfig(data.connectorCode(), data.identity(), config);
        validateConfig(handler, channelConfig);

        List<TriggerDefinition> triggers = handler.listOfTriggers(channelConfig);
        List<ToolDefinition> tools = handler.listOfTools(channelConfig);

        // Validate that the source connector and every trigger/tool actually exist for this user.
        for (TriggerDefinition t : triggers) {
            validateTrigger(userId, data.connectorCode(), data.identity(), t.triggerName());
        }
        for (ToolDefinition t : tools) {
            validateTool(userId, t.connectorCode(), t.identity(), t.toolName());
        }

        // Conflict checks before persisting anything.
        for (TriggerDefinition t : triggers) {
            AgentTriggerPolicy existing = agentTriggerPolicyRepository.findByCompositeKey(
                    data.agentId(), data.connectorCode(), data.identity(), t.triggerName(), AccessEffect.ALLOW.name());
            if (existing != null && existing.getChannelId() != null) {
                throw new ConflictStatusException(
                        "Trigger policy for '" + t.triggerName() + "' already linked to another channel");
            }
        }
        for (ToolDefinition t : tools) {
            AgentToolPolicy existing = agentToolPolicyRepository.findByCompositeKey(
                    data.agentId(), t.connectorCode(), t.identity(), t.toolName(), AccessEffect.ALLOW.name());
            if (existing != null && existing.getChannelId() != null) {
                throw new ConflictStatusException(
                        "Tool policy for '" + t.toolName() + "' already linked to another channel");
            }
        }

        Channel channel = channelRepository.save(Channel.builder()
                .userId(userId)
                .agentId(data.agentId())
                .name(data.name())
                .channelHandler(handler.name())
                .connectorCode(data.connectorCode())
                .identity(data.identity())
                .config(config)
                .build());

        String source = "channel:" + channel.getId();
        for (TriggerDefinition t : triggers) {
            upsertTriggerPolicy(userId, channel, data.connectorCode(), data.identity(),
                    t.triggerName(), source, data.inputFilter());
        }
        for (ToolDefinition t : tools) {
            upsertToolPolicy(userId, channel, t.connectorCode(), t.identity(), t.toolName(), source);
        }

        log.info("Created channel id={} handler={} for agent={} user={}",
                channel.getId(), handler.name(), data.agentId(), userId);
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
            ChannelConfig oldCfg = new ChannelConfig(channel.getConnectorCode(), channel.getIdentity(), channel.getConfig());
            ChannelConfig newCfg = new ChannelConfig(channel.getConnectorCode(), channel.getIdentity(), data.config());
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

        Channel saved = channelRepository.save(channel);

        if (data.inputFilter() != null || data.clearInputFilter()) {
            Map<String, Object> filter = data.clearInputFilter() ? null : data.inputFilter();
            for (AgentTriggerPolicy policy : agentTriggerPolicyRepository.findByChannelIdIn(List.of(channel.getId()))) {
                policy.setInputFilter(filter);
                agentTriggerPolicyRepository.save(policy);
            }
        }

        return saved;
    }

    @Transactional
    public void delete(UUID userId, UUID id) {
        Channel channel = getById(userId, id);
        agentTriggerPolicyRepository.deleteByChannelId(channel.getId());
        agentToolPolicyRepository.deleteByChannelId(channel.getId());
        channel.setDeletedAt(LocalDateTime.now());
        channelRepository.save(channel);
        log.info("Soft-deleted channel id={} (trigger/tool policies hard-deleted)", id);
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

    private void upsertTriggerPolicy(UUID userId, Channel channel, String connectorCode, String identity,
                                     String triggerName, String source, Map<String, Object> inputFilter) {
        AgentTriggerPolicy policy = agentTriggerPolicyRepository.findByCompositeKey(
                channel.getAgentId(), connectorCode, identity, triggerName, AccessEffect.ALLOW.name());
        if (policy == null) {
            policy = AgentTriggerPolicy.builder()
                    .userId(userId)
                    .agentId(channel.getAgentId())
                    .connectorCode(connectorCode)
                    .connectorIdentity(identity)
                    .triggerName(triggerName)
                    .effect(AccessEffect.ALLOW)
                    .build();
        }
        policy.setSource(source);
        policy.setChannelId(channel.getId());
        policy.setInputFilter(inputFilter);
        agentTriggerPolicyRepository.save(policy);
    }

    private void upsertToolPolicy(UUID userId, Channel channel, String connectorCode, String identity,
                                  String toolName, String source) {
        AgentToolPolicy policy = agentToolPolicyRepository.findByCompositeKey(
                channel.getAgentId(), connectorCode, identity, toolName, AccessEffect.ALLOW.name());
        if (policy == null) {
            policy = AgentToolPolicy.builder()
                    .userId(userId)
                    .agentId(channel.getAgentId())
                    .connectorCode(connectorCode)
                    .connectorIdentity(identity)
                    .toolName(toolName)
                    .effect(AccessEffect.ALLOW)
                    .build();
        }
        policy.setSource(source);
        policy.setChannelId(channel.getId());
        agentToolPolicyRepository.save(policy);
    }

    private Set<String> triggerKeys(ChannelHandler handler, ChannelConfig config) {
        return handler.listOfTriggers(config).stream()
                .map(TriggerDefinition::triggerName)
                .collect(Collectors.toSet());
    }

    private Set<String> toolKeys(ChannelHandler handler, ChannelConfig config) {
        return handler.listOfTools(config).stream()
                .map(t -> t.connectorCode() + "|" + t.identity() + "|" + t.toolName())
                .collect(Collectors.toSet());
    }

    private void validateTrigger(UUID userId, String connectorCode, String identity, String triggerName) {
        Connector connector = connectorRepository.findById(connectorCode)
                .orElseThrow(() -> new NotFoundStatusException("Connector not found: " + connectorCode));
        Set<String> available = lookupTriggerNames(connector, userId, identity);
        if (!available.contains(triggerName)) {
            throw new BadRequestStatusException(
                    "Trigger '" + triggerName + "' not available on connector '" + connectorCode + "'");
        }
    }

    private void validateTool(UUID userId, String connectorCode, String identity, String toolName) {
        Connector connector = connectorRepository.findById(connectorCode)
                .orElseThrow(() -> new NotFoundStatusException("Reply connector not found: " + connectorCode));
        Set<String> available = lookupToolNames(connector, userId, identity);
        if (!available.contains(toolName)) {
            throw new BadRequestStatusException(
                    "Tool '" + toolName + "' not available on connector '" + connectorCode + "'");
        }
    }

    private Set<String> lookupTriggerNames(Connector connector, UUID userId, String identity) {
        return switch (connector.getType()) {
            case APP -> {
                App app = loadApp(userId, identity);
                yield app.getTriggers() != null ? app.getTriggers().keySet() : Set.of();
            }
            case INTEGRATION -> {
                loadIntegration(userId, connector.getCode(), identity);
                ConnectorHandler handler = connectorRegistry.findHandler(connector.getCode())
                        .orElseThrow(() -> new BadRequestStatusException("Unknown integration: " + connector.getCode()));
                yield handler.getTriggers().keySet();
            }
            case INTERNAL_SERVICE, LOOPBACK ->
                    throw new BadRequestStatusException("Connector type does not support triggers: " + connector.getType());
        };
    }

    private Set<String> lookupToolNames(Connector connector, UUID userId, String identity) {
        return switch (connector.getType()) {
            case APP -> {
                App app = loadApp(userId, identity);
                yield app.getTools() != null ? app.getTools().keySet() : Set.of();
            }
            case INTEGRATION -> {
                loadIntegration(userId, connector.getCode(), identity);
                ConnectorHandler handler = connectorRegistry.findHandler(connector.getCode())
                        .orElseThrow(() -> new BadRequestStatusException("Unknown integration: " + connector.getCode()));
                yield handler.getTools().keySet();
            }
            case INTERNAL_SERVICE, LOOPBACK ->
                    throw new BadRequestStatusException("Connector type does not support tools: " + connector.getType());
        };
    }

    private App loadApp(UUID userId, String identity) {
        UUID identityId = parseUuid(identity, "identity");
        App app = appRepository.findByIdAndUserIdNotDeleted(identityId, userId)
                .orElseThrow(() -> new NotFoundStatusException("App not found: " + identity));
        if (!app.isActive()) {
            throw new BadRequestStatusException("App is not active: " + identity);
        }
        return app;
    }

    private IntegrationCredentials loadIntegration(UUID userId, String connectorCode, String identity) {
        UUID identityId = parseUuid(identity, "identity");
        IntegrationCredentials credentials = integrationCredentialsRepository
                .findByIdAndUserIdNotDeleted(identityId, userId)
                .orElseThrow(() -> new NotFoundStatusException("Integration not found: " + identity));
        if (!credentials.getConnectorCode().equals(connectorCode)) {
            throw new BadRequestStatusException(
                    "Integration connector mismatch: expected " + connectorCode + " got " + credentials.getConnectorCode());
        }
        if (!credentials.isActive()) {
            throw new BadRequestStatusException("Integration is not active: " + identity);
        }
        return credentials;
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
            String identity,
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
