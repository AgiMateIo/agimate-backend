package ru.agimate.deviceapi.service.channel;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.ConflictStatusException;
import ru.agimate.common.rest.error.ForbiddenStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.deviceapi.abac.AccessEffect;
import ru.agimate.deviceapi.connectors.integrations.IntegrationHandler;
import ru.agimate.deviceapi.connectors.integrations.IntegrationsRegistry;
import ru.agimate.deviceapi.controller.manage.dto.channel.ChannelResponse;
import ru.agimate.deviceapi.database.entities.Agent;
import ru.agimate.deviceapi.database.entities.AgentToolPolicy;
import ru.agimate.deviceapi.database.entities.AgentTriggerPolicy;
import ru.agimate.deviceapi.database.entities.App;
import ru.agimate.deviceapi.database.entities.Channel;
import ru.agimate.deviceapi.database.entities.Connector;
import ru.agimate.deviceapi.database.entities.IntegrationCredentials;
import ru.agimate.deviceapi.database.repositories.AgentRepository;
import ru.agimate.deviceapi.database.repositories.AgentToolPolicyRepository;
import ru.agimate.deviceapi.database.repositories.AgentTriggerPolicyRepository;
import ru.agimate.deviceapi.database.repositories.AppRepository;
import ru.agimate.deviceapi.database.repositories.ChannelRepository;
import ru.agimate.deviceapi.database.repositories.ConnectorRepository;
import ru.agimate.deviceapi.database.repositories.IntegrationCredentialsRepository;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

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
    private final IntegrationsRegistry integrationsRegistry;
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

    public Optional<Channel> findActiveByTriggerKey(UUID userId, UUID agentId,
                                                    String connectorCode, String identity, String triggerName) {
        return channelRepository.findActiveByTriggerKey(userId, agentId, connectorCode, identity, triggerName);
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
                        nameById.get(tryParseUuid(c.getTriggerIdentity())),
                        nameById.get(tryParseUuid(c.getReplyIdentity())),
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
            UUID t = tryParseUuid(c.getTriggerIdentity());
            if (t != null) identityIds.add(t);
            UUID r = tryParseUuid(c.getReplyIdentity());
            if (r != null) identityIds.add(r);
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

        validateMessageField(data.triggerMessageField());
        validateTriggerSource(userId, data.triggerConnectorCode(), data.triggerIdentity(), data.triggerName());
        validateReplyTarget(userId, data.replyConnectorCode(), data.replyIdentity(), data.replyToolName());

        channelRepository.findActiveByTriggerKey(userId, data.agentId(),
                        data.triggerConnectorCode(), data.triggerIdentity(), data.triggerName())
                .ifPresent(c -> {
                    throw new ConflictStatusException(
                            "Channel for this agent and trigger already exists: " + c.getId());
                });

        AgentTriggerPolicy existingPolicy = agentTriggerPolicyRepository.findByCompositeKey(
                data.agentId(), data.triggerConnectorCode(), data.triggerIdentity(),
                data.triggerName(), AccessEffect.ALLOW.name());
        if (existingPolicy != null && existingPolicy.getChannelId() != null) {
            throw new ConflictStatusException(
                    "Conflicting agent trigger policy already linked to another channel");
        }

        AgentToolPolicy existingToolPolicy = agentToolPolicyRepository.findByCompositeKey(
                data.agentId(), data.replyConnectorCode(), data.replyIdentity(),
                data.replyToolName(), AccessEffect.ALLOW.name());
        if (existingToolPolicy != null && existingToolPolicy.getChannelId() != null) {
            throw new ConflictStatusException(
                    "Conflicting agent tool policy already linked to another channel");
        }

        Channel channel = Channel.builder()
                .userId(userId)
                .agentId(data.agentId())
                .name(data.name())
                .triggerConnectorCode(data.triggerConnectorCode())
                .triggerIdentity(data.triggerIdentity())
                .triggerName(data.triggerName())
                .triggerMessageField(data.triggerMessageField())
                .replyConnectorCode(data.replyConnectorCode())
                .replyIdentity(data.replyIdentity())
                .replyToolName(data.replyToolName())
                .replyToolParams(data.replyToolParams())
                .build();
        channel = channelRepository.save(channel);

        if (existingPolicy != null) {
            existingPolicy.setChannelId(channel.getId());
            existingPolicy.setInputFilter(data.inputFilter());
            agentTriggerPolicyRepository.save(existingPolicy);
        } else {
            AgentTriggerPolicy policy = AgentTriggerPolicy.builder()
                    .userId(userId)
                    .agentId(data.agentId())
                    .connectorCode(data.triggerConnectorCode())
                    .connectorIdentity(data.triggerIdentity())
                    .triggerName(data.triggerName())
                    .effect(AccessEffect.ALLOW)
                    .source("channel:" + channel.getId())
                    .channelId(channel.getId())
                    .inputFilter(data.inputFilter())
                    .build();
            agentTriggerPolicyRepository.save(policy);
        }

        if (existingToolPolicy != null) {
            existingToolPolicy.setChannelId(channel.getId());
            agentToolPolicyRepository.save(existingToolPolicy);
        } else {
            AgentToolPolicy toolPolicy = AgentToolPolicy.builder()
                    .userId(userId)
                    .agentId(data.agentId())
                    .connectorCode(data.replyConnectorCode())
                    .connectorIdentity(data.replyIdentity())
                    .toolName(data.replyToolName())
                    .effect(AccessEffect.ALLOW)
                    .source("channel:" + channel.getId())
                    .channelId(channel.getId())
                    .build();
            agentToolPolicyRepository.save(toolPolicy);
        }

        log.info("Created channel id={} for agent={} user={}", channel.getId(), data.agentId(), userId);
        return channel;
    }

    @Transactional
    public Channel update(UUID userId, UUID id, UpdateChannelData data) {
        Channel channel = getById(userId, id);

        if (data.name() != null) {
            channel.setName(data.name());
        }
        if (data.triggerMessageField() != null) {
            validateMessageField(data.triggerMessageField());
            channel.setTriggerMessageField(data.triggerMessageField());
        }
        if (data.replyToolParams() != null) {
            channel.setReplyToolParams(data.replyToolParams());
        }
        Channel saved = channelRepository.save(channel);

        if (data.inputFilter() != null || data.clearInputFilter()) {
            AgentTriggerPolicy policy = agentTriggerPolicyRepository.findByCompositeKey(
                    channel.getAgentId(), channel.getTriggerConnectorCode(),
                    channel.getTriggerIdentity(), channel.getTriggerName(), AccessEffect.ALLOW.name());
            if (policy != null && channel.getId().equals(policy.getChannelId())) {
                policy.setInputFilter(data.clearInputFilter() ? null : data.inputFilter());
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

    private void validateMessageField(String messageField) {
        if (messageField == null || messageField.isBlank()) {
            throw new BadRequestStatusException("triggerMessageField is required");
        }
    }

    private void validateTriggerSource(UUID userId, String connectorCode, String identity, String triggerName) {
        Connector connector = connectorRepository.findById(connectorCode)
                .orElseThrow(() -> new NotFoundStatusException("Connector not found: " + connectorCode));

        Set<String> availableTriggers = lookupTriggerNames(connector, userId, identity);
        if (!availableTriggers.contains(triggerName)) {
            throw new BadRequestStatusException(
                    "Trigger '" + triggerName + "' not available on connector '" + connectorCode + "'");
        }
    }

    private void validateReplyTarget(UUID userId, String connectorCode, String identity, String toolName) {
        Connector connector = connectorRepository.findById(connectorCode)
                .orElseThrow(() -> new NotFoundStatusException("Reply connector not found: " + connectorCode));

        Set<String> availableTools = lookupToolNames(connector, userId, identity);
        if (!availableTools.contains(toolName)) {
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
                IntegrationHandler handler = integrationsRegistry.findHandler(connector.getCode())
                        .orElseThrow(() -> new BadRequestStatusException("Unknown integration: " + connector.getCode()));
                yield handler.getPredefinedTriggers() != null ? handler.getPredefinedTriggers().keySet() : Set.of();
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
                IntegrationHandler handler = integrationsRegistry.findHandler(connector.getCode())
                        .orElseThrow(() -> new BadRequestStatusException("Unknown integration: " + connector.getCode()));
                yield handler.getPredefinedTools() != null ? handler.getPredefinedTools().keySet() : Set.of();
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
            String triggerConnectorCode,
            String triggerIdentity,
            String triggerName,
            String triggerMessageField,
            String replyConnectorCode,
            String replyIdentity,
            String replyToolName,
            Map<String, Object> replyToolParams,
            Map<String, Object> inputFilter
    ) {}

    public record UpdateChannelData(
            String name,
            String triggerMessageField,
            Map<String, Object> replyToolParams,
            Map<String, Object> inputFilter,
            boolean clearInputFilter
    ) {}
}
