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
import ru.agimate.deviceapi.database.entities.Agent;
import ru.agimate.deviceapi.database.entities.AgentTriggerPolicy;
import ru.agimate.deviceapi.database.entities.App;
import ru.agimate.deviceapi.database.entities.Channel;
import ru.agimate.deviceapi.database.entities.Connector;
import ru.agimate.deviceapi.database.entities.IntegrationCredentials;
import ru.agimate.deviceapi.database.repositories.AgentRepository;
import ru.agimate.deviceapi.database.repositories.AgentTriggerPolicyRepository;
import ru.agimate.deviceapi.database.repositories.AppRepository;
import ru.agimate.deviceapi.database.repositories.ChannelRepository;
import ru.agimate.deviceapi.database.repositories.ConnectorRepository;
import ru.agimate.deviceapi.database.repositories.IntegrationCredentialsRepository;

import java.time.LocalDateTime;
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

    public Channel getByPubId(UUID userPubId, UUID pubId) {
        Channel channel = channelRepository.findByPubIdAndDeletedAtIsNull(pubId)
                .orElseThrow(() -> new NotFoundStatusException("Channel not found"));
        if (!channel.getUserPubId().equals(userPubId)) {
            throw new ForbiddenStatusException("Access denied");
        }
        return channel;
    }

    public Channel getByIdForUser(UUID userPubId, Long id) {
        Channel channel = channelRepository.findById(id)
                .orElseThrow(() -> new NotFoundStatusException("Channel not found"));
        if (!channel.getUserPubId().equals(userPubId)) {
            throw new ForbiddenStatusException("Access denied");
        }
        return channel;
    }

    public List<Channel> listForUser(UUID userPubId) {
        return channelRepository.findByUserPubIdAndDeletedAtIsNullOrderByCreatedAtDesc(userPubId);
    }

    public List<Channel> listForAgent(UUID agentPubId) {
        return channelRepository.findByAgentPubIdAndDeletedAtIsNullOrderByCreatedAtDesc(agentPubId);
    }

    public Optional<Channel> findActiveByTriggerKey(UUID userPubId, UUID agentPubId,
                                                    String connectorCode, String identity, String triggerName) {
        return channelRepository.findActiveByTriggerKey(userPubId, agentPubId, connectorCode, identity, triggerName);
    }

    @Transactional
    public Channel create(UUID userPubId, CreateChannelData data) {
        Agent agent = agentRepository.findByPubId(data.agentPubId())
                .orElseThrow(() -> new NotFoundStatusException("Agent not found"));
        if (!agent.getUserPubId().equals(userPubId)) {
            throw new ForbiddenStatusException("Access denied to agent");
        }

        validateMessageField(data.triggerMessageField());
        validateTriggerSource(userPubId, data.triggerConnectorCode(), data.triggerIdentity(), data.triggerName());
        validateReplyTarget(userPubId, data.replyConnectorCode(), data.replyIdentity(), data.replyToolName());

        channelRepository.findActiveByTriggerKey(userPubId, data.agentPubId(),
                        data.triggerConnectorCode(), data.triggerIdentity(), data.triggerName())
                .ifPresent(c -> {
                    throw new ConflictStatusException(
                            "Channel for this agent and trigger already exists: " + c.getPubId());
                });

        AgentTriggerPolicy existingPolicy = agentTriggerPolicyRepository.findByCompositeKey(
                data.agentPubId(), data.triggerConnectorCode(), data.triggerIdentity(),
                data.triggerName(), AccessEffect.ALLOW.name());
        if (existingPolicy != null && existingPolicy.getChannelId() != null) {
            throw new ConflictStatusException(
                    "Conflicting agent trigger policy already linked to another channel");
        }

        Channel channel = Channel.builder()
                .userPubId(userPubId)
                .agentPubId(data.agentPubId())
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
                    .userPubId(userPubId)
                    .agentPubId(data.agentPubId())
                    .connectorCode(data.triggerConnectorCode())
                    .connectorIdentity(data.triggerIdentity())
                    .triggerName(data.triggerName())
                    .effect(AccessEffect.ALLOW)
                    .source("channel:" + channel.getPubId())
                    .channelId(channel.getId())
                    .inputFilter(data.inputFilter())
                    .build();
            agentTriggerPolicyRepository.save(policy);
        }

        log.info("Created channel pubId={} for agent={} user={}", channel.getPubId(), data.agentPubId(), userPubId);
        return channel;
    }

    @Transactional
    public Channel update(UUID userPubId, UUID pubId, UpdateChannelData data) {
        Channel channel = getByPubId(userPubId, pubId);

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
                    channel.getAgentPubId(), channel.getTriggerConnectorCode(),
                    channel.getTriggerIdentity(), channel.getTriggerName(), AccessEffect.ALLOW.name());
            if (policy != null && channel.getId().equals(policy.getChannelId())) {
                policy.setInputFilter(data.clearInputFilter() ? null : data.inputFilter());
                agentTriggerPolicyRepository.save(policy);
            }
        }

        return saved;
    }

    @Transactional
    public void delete(UUID userPubId, UUID pubId) {
        Channel channel = getByPubId(userPubId, pubId);
        channel.setDeletedAt(LocalDateTime.now());
        channelRepository.save(channel);
        log.info("Soft-deleted channel pubId={}", pubId);
    }

    private void validateMessageField(String messageField) {
        if (messageField == null || messageField.isBlank()) {
            throw new BadRequestStatusException("triggerMessageField is required");
        }
    }

    private void validateTriggerSource(UUID userPubId, String connectorCode, String identity, String triggerName) {
        Connector connector = connectorRepository.findById(connectorCode)
                .orElseThrow(() -> new NotFoundStatusException("Connector not found: " + connectorCode));

        Set<String> availableTriggers = lookupTriggerNames(connector, userPubId, identity);
        if (!availableTriggers.contains(triggerName)) {
            throw new BadRequestStatusException(
                    "Trigger '" + triggerName + "' not available on connector '" + connectorCode + "'");
        }
    }

    private void validateReplyTarget(UUID userPubId, String connectorCode, String identity, String toolName) {
        Connector connector = connectorRepository.findById(connectorCode)
                .orElseThrow(() -> new NotFoundStatusException("Reply connector not found: " + connectorCode));

        Set<String> availableTools = lookupToolNames(connector, userPubId, identity);
        if (!availableTools.contains(toolName)) {
            throw new BadRequestStatusException(
                    "Tool '" + toolName + "' not available on connector '" + connectorCode + "'");
        }
    }

    private Set<String> lookupTriggerNames(Connector connector, UUID userPubId, String identity) {
        return switch (connector.getType()) {
            case APP -> {
                App app = loadApp(userPubId, identity);
                yield app.getTriggers() != null ? app.getTriggers().keySet() : Set.of();
            }
            case INTEGRATION -> {
                loadIntegration(userPubId, connector.getCode(), identity);
                IntegrationHandler handler = integrationsRegistry.findHandler(connector.getCode())
                        .orElseThrow(() -> new BadRequestStatusException("Unknown integration: " + connector.getCode()));
                yield handler.getPredefinedTriggers() != null ? handler.getPredefinedTriggers().keySet() : Set.of();
            }
            case INTERNAL_SERVICE, LOOPBACK ->
                    throw new BadRequestStatusException("Connector type does not support triggers: " + connector.getType());
        };
    }

    private Set<String> lookupToolNames(Connector connector, UUID userPubId, String identity) {
        return switch (connector.getType()) {
            case APP -> {
                App app = loadApp(userPubId, identity);
                yield app.getTools() != null ? app.getTools().keySet() : Set.of();
            }
            case INTEGRATION -> {
                loadIntegration(userPubId, connector.getCode(), identity);
                IntegrationHandler handler = integrationsRegistry.findHandler(connector.getCode())
                        .orElseThrow(() -> new BadRequestStatusException("Unknown integration: " + connector.getCode()));
                yield handler.getPredefinedTools() != null ? handler.getPredefinedTools().keySet() : Set.of();
            }
            case INTERNAL_SERVICE, LOOPBACK ->
                    throw new BadRequestStatusException("Connector type does not support tools: " + connector.getType());
        };
    }

    private App loadApp(UUID userPubId, String identity) {
        UUID identityPubId = parseUuid(identity, "identity");
        App app = appRepository.findByPubIdAndUserPubIdNotDeleted(identityPubId, userPubId)
                .orElseThrow(() -> new NotFoundStatusException("App not found: " + identity));
        if (!app.isActive()) {
            throw new BadRequestStatusException("App is not active: " + identity);
        }
        return app;
    }

    private IntegrationCredentials loadIntegration(UUID userPubId, String connectorCode, String identity) {
        UUID identityPubId = parseUuid(identity, "identity");
        IntegrationCredentials credentials = integrationCredentialsRepository
                .findByPubIdAndUserPubIdNotDeleted(identityPubId, userPubId)
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
            UUID agentPubId,
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
