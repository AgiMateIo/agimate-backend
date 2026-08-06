package ru.agimate.controlapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.ForbiddenStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.common.rest.error.ValidationErrorStatusException;
import ru.agimate.controlapi.abac.ConnectionAccessEvaluator;
import ru.agimate.controlapi.connectors.core.ConnectorRegistry;
import ru.agimate.controlapi.connectors.core.ToolProvider;
import ru.agimate.controlapi.connectors.core.TriggerProvider;
import ru.agimate.controlapi.controller.agent.dto.AgentConfigResponse;
import ru.agimate.controlapi.controller.agent.dto.AgentContextResponse;
import ru.agimate.controlapi.controller.agent.dto.ToolDefinition;
import ru.agimate.controlapi.controller.manage.dto.AgentResponse;
import ru.agimate.controlapi.controller.manage.dto.AgentSkillSummary;
import ru.agimate.controlapi.controller.manage.dto.CreateAgentRequest;
import ru.agimate.controlapi.controller.manage.dto.UpdateAgentRequest;
import ru.agimate.controlapi.controller.manage.dto.llm.AgentLlmResponse;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.entities.AgentConnection;
import ru.agimate.controlapi.database.entities.AgenticTeam;
import ru.agimate.controlapi.database.entities.App;
import ru.agimate.controlapi.database.entities.Connection;
import ru.agimate.controlapi.database.entities.ConnectionTool;
import ru.agimate.controlapi.database.entities.ConnectionTrigger;
import ru.agimate.controlapi.database.entities.Connector;
import ru.agimate.controlapi.database.enums.AgentType;
import ru.agimate.controlapi.service.dto.agent.AgentCreateCommand;
import ru.agimate.controlapi.service.dto.agent.AgentUpdateCommand;
import ru.agimate.controlapi.database.enums.PolicyKind;
import ru.agimate.controlapi.database.repositories.AgentConnectionRepository;
import ru.agimate.controlapi.database.repositories.AgentPresetRepository;
import ru.agimate.controlapi.database.repositories.AgentRepository;
import ru.agimate.controlapi.database.repositories.AgentSkillRepository;
import ru.agimate.controlapi.database.repositories.ChannelRepository;
import ru.agimate.controlapi.database.repositories.AgenticTeamRepository;
import ru.agimate.controlapi.database.repositories.AppRepository;
import ru.agimate.controlapi.database.repositories.ConnectionRepository;
import ru.agimate.controlapi.database.repositories.ConnectionToolRepository;
import ru.agimate.controlapi.database.repositories.ConnectionTriggerRepository;
import ru.agimate.controlapi.database.repositories.ConnectorJobRepository;
import ru.agimate.controlapi.database.repositories.ConnectorRepository;
import ru.agimate.controlapi.database.repositories.SecretRepository;
import ru.agimate.controlapi.database.entities.Secret;
import ru.agimate.controlapi.service.connection.ConnectionBindingService;
import ru.agimate.controlapi.service.secret.SecretService;
import ru.agimate.controlapi.util.AgentNames;
import ru.agimate.controlapi.util.AppKeyUtils;
import ru.agimate.controlapi.util.GeneratedAppKey;

import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AgentService {

    public static final String AGENT_KEY_PREFIX = "agnt";
    /** The Authorization header of outbound webhooks (a single value, AAD owner = agent.id). */
    public static final String WEBHOOK_AUTH_SECRET_ENTITY = "agent_webhook_auth";

    private final AgentRepository agentRepository;
    private final AgentConnectionRepository agentConnectionRepository;
    private final ConnectionRepository connectionRepository;
    private final ConnectorRepository connectorRepository;
    private final ConnectorRegistry connectorRegistry;
    private final ConnectionToolRepository connectionToolRepository;
    private final ConnectionTriggerRepository connectionTriggerRepository;
    private final ConnectionAccessEvaluator accessEvaluator;
    private final ConnectionBindingService connectionBindingService;
    private final AgentSkillRepository agentSkillRepository;
    private final AgentSkillService agentSkillService;
    private final AgentPresetRepository agentPresetRepository;
    private final AgenticTeamRepository agenticTeamRepository;
    private final AppRepository appRepository;
    private final AgentLlmService agentLlmService;
    private final ConnectorJobRepository connectorJobRepository;
    private final ChannelRepository channelRepository;
    private final AgentDeliveryService agentDeliveryService;
    private final SecretRepository secretRepository;
    private final SecretService secretService;

    public Page<AgentResponse> getAllForUser(UUID userId, UUID agenticTeamId, String search, int page, int size) {
        if (agenticTeamId != null) {
            agenticTeamRepository.findById(agenticTeamId)
                    .orElseThrow(() -> new NotFoundStatusException("Agentic team not found"));
        }
        String normalizedSearch = (search == null || search.isBlank()) ? null : search.trim();
        Page<Agent> agents = agentRepository.searchForUser(
                userId, agenticTeamId, normalizedSearch, PageRequest.of(page, size));

        List<UUID> teamIds = agents.getContent().stream()
                .map(Agent::getAgenticTeamId)
                .filter(id -> id != null)
                .distinct()
                .toList();
        Map<UUID, AgenticTeam> teamsById = agenticTeamRepository.findAllById(teamIds).stream()
                .collect(Collectors.toMap(AgenticTeam::getId, Function.identity()));

        List<UUID> agentIds = agents.getContent().stream().map(Agent::getId).toList();
        Map<UUID, List<AgentSkillSummary>> skillsByAgent = loadSkillSummaries(agentIds);
        Map<UUID, List<AgentLlmResponse>> llmsByAgent = agentLlmService.listForAgents(agents.getContent());

        return agents.map(agent -> {
            var team = agent.getAgenticTeamId() != null ? teamsById.get(agent.getAgenticTeamId()) : null;
            var skills = skillsByAgent.getOrDefault(agent.getId(), List.of());
            var llms = llmsByAgent.getOrDefault(agent.getId(), List.of());
            return AgentResponse.from(agent, team, skills, llms);
        });
    }

    private Map<UUID, List<AgentSkillSummary>> loadSkillSummaries(Collection<UUID> agentIds) {
        if (agentIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<AgentSkillSummary>> result = new HashMap<>();
        for (Object[] row : agentSkillRepository.findSkillSummariesByAgentIdIn(agentIds)) {
            UUID agentId = (UUID) row[0];
            UUID skillId = (UUID) row[1];
            String skillName = (String) row[2];
            result.computeIfAbsent(agentId, k -> new ArrayList<>())
                    .add(new AgentSkillSummary(skillId, skillName));
        }
        return result;
    }

    public Agent findById(UUID id) {
        return agentRepository.findById(id)
                .orElseThrow(() -> new NotFoundStatusException("Agent not found"));
    }

    public AgentResponse getById(UUID id, UUID userId) {
        Agent agent = findById(id);
        if (!agent.getUserId().equals(userId)) {
            throw new NotFoundStatusException("Agent not found");
        }
        var team = resolveTeam(agent.getAgenticTeamId());
        var skills = loadSkillSummaries(List.of(id)).getOrDefault(id, List.of());
        var llms = agentLlmService.listForAgents(List.of(agent)).getOrDefault(id, List.of());
        return AgentResponse.from(agent, team, skills, llms);
    }

    public AgentConfigResponse getConfigById(UUID agentId) {
        Agent agent = findById(agentId);

        Set<String> allowedToolNames = availableToolNames(agent);
        Map<String, ToolDefinition> toolDefinitionMap = buildToolDefinitionMap(agent.getUserId());

        List<ToolDefinition> toolDefinitions = allowedToolNames.stream()
                .map(name -> toolDefinitionMap.getOrDefault(name,
                        new ToolDefinition(name, null, null)))
                .toList();

        List<String> triggerNames = List.copyOf(availableTriggerNames(agent));

        return new AgentConfigResponse(
                agent.getId(),
                agent.getInstructions(),
                toolDefinitions,
                triggerNames
        );
    }

    public AgentContextResponse getContextById(UUID agentId) {
        Agent agent = findById(agentId);

        AgentContextResponse.Self self = new AgentContextResponse.Self(
                agent.getId(),
                agent.getName(),
                agent.getDescription(),
                agent.getInstructions()
        );

        AgentContextResponse.Team team = null;
        List<AgentContextResponse.TeamAgent> teamAgents = List.of();

        if (agent.getAgenticTeamId() != null) {
            AgenticTeam teamEntity = agenticTeamRepository.findById(agent.getAgenticTeamId()).orElse(null);
            if (teamEntity != null) {
                team = new AgentContextResponse.Team(
                        teamEntity.getId(),
                        teamEntity.getName(),
                        teamEntity.getDescription()
                );
                teamAgents = agentRepository
                        .findByUserIdAndAgenticTeamId(agent.getUserId(), teamEntity.getId())
                        .stream()
                        .map(a -> new AgentContextResponse.TeamAgent(
                                a.getId(),
                                a.getName(),
                                a.getDescription()
                        ))
                        .toList();
            }
        }

        return new AgentContextResponse(self, team, teamAgents);
    }

    public List<ToolDefinition> getAvailableTools(UUID agentId) {
        Agent agent = findById(agentId);

        Set<String> allowedToolNames = availableToolNames(agent);
        Map<String, ToolDefinition> toolDefinitionMap = buildToolDefinitionMap(agent.getUserId());

        return allowedToolNames.stream()
                .map(name -> toolDefinitionMap.getOrDefault(name,
                        new ToolDefinition(name, null, null)))
                .toList();
    }

    /**
     * The tools available to an agent = the union of the tools of every bound ({@code agent_connections})
     * active instance, minus the DENY rules (default-allow). The source of the names follows
     * {@code definitionBinding}: STATIC — reflection over the handler, DYNAMIC —
     * {@code connection_tools}.
     */
    private Set<String> availableToolNames(Agent agent) {
        return availableNames(agent, PolicyKind.TOOL);
    }

    private Set<String> availableTriggerNames(Agent agent) {
        return availableNames(agent, PolicyKind.TRIGGER);
    }

    private Set<String> availableNames(Agent agent, PolicyKind kind) {
        Set<String> names = new LinkedHashSet<>();
        for (AgentConnection binding : agentConnectionRepository.findActiveByAgentId(agent.getId())) {
            Connection connection = connectionRepository.findByIdNotDeleted(binding.getConnectionId()).orElse(null);
            if (connection == null || !connection.isUsable()) {
                continue;
            }
            Connector connector = connectorRepository.findById(connection.getConnectorCode()).orElse(null);
            if (connector == null || connector.getDefinitionBinding() == null) {
                continue;
            }
            for (String name : namesFor(connector, connection, kind)) {
                if (accessEvaluator.evaluate(agent.getId(), connection.getId(), kind, name).allowed()) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    private Set<String> namesFor(Connector connector, Connection connection, PolicyKind kind) {
        return switch (connector.getDefinitionBinding()) {
            case STATIC -> kind == PolicyKind.TOOL
                    ? connectorRegistry.findCapability(connector.getCode(), ToolProvider.class)
                            .map(p -> p.getTools().keySet()).orElse(Set.of())
                    : connectorRegistry.findCapability(connector.getCode(), TriggerProvider.class)
                            .map(p -> p.getTriggers().keySet()).orElse(Set.of());
            case DYNAMIC -> kind == PolicyKind.TOOL
                    ? connectionToolRepository.findActiveByConnectionId(connection.getId()).stream()
                            .map(ConnectionTool::getName).collect(Collectors.toSet())
                    : connectionTriggerRepository.findActiveByConnectionId(connection.getId()).stream()
                            .map(ConnectionTrigger::getName).collect(Collectors.toSet());
        };
    }

    @SuppressWarnings("unchecked")
    private Map<String, ToolDefinition> buildToolDefinitionMap(UUID userId) {
        List<App> apps = appRepository.findByUserIdNotDeleted(userId);
        Map<String, ToolDefinition> map = new LinkedHashMap<>();

        for (App app : apps) {
            if (app.getTools() == null) continue;
            for (var entry : app.getTools().entrySet()) {
                String toolName = entry.getKey();
                var value = (Map<String, Object>) entry.getValue();
                String description = value.getOrDefault("description", "").toString();
                List<String> params = value.get("params") instanceof List<?> list
                        ? list.stream().map(Object::toString).toList()
                        : List.of();
                map.put(toolName, new ToolDefinition(toolName, description, params));
            }
        }

        return map;
    }

    @Transactional
    public AgentCreateResult create(UUID userId, CreateAgentRequest request) {
        return create(userId, new AgentCreateCommand(
                request.name(), request.description(), request.instructions(), request.type(),
                request.webhookUrl(), request.webhookAuthHeader(), request.agenticTeamId(),
                request.skillIds(), request.presetName()));
    }

    @Transactional
    public AgentCreateResult create(UUID userId, AgentCreateCommand command) {
        AgentType type = command.type() != null
                ? command.type() : AgentType.CENTRIFUGO;
        validateWebhookFields(type, command.webhookUrl());

        AgenticTeam team = null;
        if (command.agenticTeamId() != null) {
            team = agenticTeamRepository.findById(command.agenticTeamId())
                    .orElseThrow(() -> new NotFoundStatusException("Agentic team not found"));
            if (!team.getUserId().equals(userId)) {
                throw new ForbiddenStatusException("Access denied to the specified team");
            }
        }

        String presetName = validatedPresetName(command.presetName());

        GeneratedAppKey generatedKey = AppKeyUtils.generate(AGENT_KEY_PREFIX);

        Agent agent = Agent.builder()
                .keyHash(generatedKey.secretHash())
                .keyId(generatedKey.keyId())
                .userId(userId)
                .name(AgentNames.unique(command.name(), agentRepository.findNamesByUserId(userId)))
                .description(command.description())
                .instructions(command.instructions())
                .type(type)
                .webhookUrl(command.webhookUrl())
                .agenticTeamId(team != null ? team.getId() : null)
                .presetName(presetName)
                .build();
        // The id is generated by the database — the auth header's secret (AAD-bound to agent.id) is stored after the save.
        agent = agentRepository.save(agent);
        applyWebhookAuthHeader(agent, command.webhookAuthHeader());

        // The wizard's skills go in the same transaction: the agent is created with its final set at once.
        if (command.skillIds() != null) {
            for (UUID skillId : new LinkedHashSet<>(command.skillIds())) {
                agentSkillService.create(agent.getId(), skillId, userId);
            }
        }

        log.info("Created agent id={}, user={}, preset={}", agent.getId(), userId, presetName);
        return new AgentCreateResult(agent, team, generatedKey.fullKey());
    }

    /** A preset is only a funnel label, but the label must exist: a typo is a BadRequest. */
    private String validatedPresetName(String presetName) {
        if (presetName == null || presetName.isBlank()) {
            return null;
        }
        String name = presetName.strip();
        agentPresetRepository.findByName(name)
                .orElseThrow(() -> new BadRequestStatusException("Unknown preset name: " + name));
        return name;
    }

    @Transactional
    public AgentCreateResult regenerateKey(UUID id, UUID userId) {
        Agent agent = agentRepository.findById(id)
                .orElseThrow(() -> new NotFoundStatusException("Agent not found"));

        if (!agent.getUserId().equals(userId)) {
            throw new ForbiddenStatusException("Access denied");
        }

        GeneratedAppKey generatedKey = AppKeyUtils.generate(AGENT_KEY_PREFIX);
        agent.setKeyHash(generatedKey.secretHash());
        agent.setKeyId(generatedKey.keyId());
        agent = agentRepository.save(agent);

        var team = resolveTeam(agent.getAgenticTeamId());

        log.info("Regenerated key for agent id={}", id);
        return new AgentCreateResult(agent, team, generatedKey.fullKey());
    }

    @Transactional
    public AgentResponse update(UUID id, UUID userId, UpdateAgentRequest request) {
        return update(id, userId, new AgentUpdateCommand(
                request.name(), request.description(), request.instructions(), request.type(),
                request.webhookUrl(), request.webhookAuthHeader(), request.enabled()));
    }

    @Transactional
    public AgentResponse update(UUID id, UUID userId, AgentUpdateCommand command) {
        Agent agent = agentRepository.findById(id)
                .orElseThrow(() -> new NotFoundStatusException("Agent not found"));

        if (!agent.getUserId().equals(userId)) {
            throw new ForbiddenStatusException("Access denied");
        }

        AgentType type = command.type() != null
                ? command.type() : agent.getType();
        validateWebhookFields(type, command.webhookUrl());
        requireChannelsCanFollowTheType(agent, type);

        if (command.name() != null) {
            agent.setName(command.name());
        }
        agent.setDescription(command.description());
        agent.setInstructions(command.instructions());
        agent.setType(type);
        agent.setWebhookUrl(command.webhookUrl());
        applyWebhookAuthHeader(agent, command.webhookAuthHeader());
        if (command.enabled() != null) {
            agent.setEnabled(command.enabled());
        }
        agent = agentRepository.save(agent);

        var team = resolveTeam(agent.getAgenticTeamId());
        var skills = loadSkillSummaries(List.of(id)).getOrDefault(id, List.of());
        var llms = agentLlmService.listForAgents(List.of(agent)).getOrDefault(id, List.of());

        log.info("Updated agent id={}", id);
        return AgentResponse.from(agent, team, skills, llms);
    }

    @Transactional
    public void delete(UUID id, UUID userId) {
        Agent agent = agentRepository.findById(id)
                .orElseThrow(() -> new NotFoundStatusException("Agent not found"));

        if (!agent.getUserId().equals(userId)) {
            throw new ForbiddenStatusException("Access denied");
        }

        // We drop every binding together with its policies: the mode rows and the external instances live on
        // (they do not belong to the agent), but an orphaned binding's rules must not remain.
        connectionBindingService.detachAgent(agent.getId());
        connectorJobRepository.deleteByAgentId(agent.getId()); // dynamic AGENT jobs
        accessEvaluator.invalidateByAgent(agent.getId());
        // A soft delete: the row stays (every FK onto agents remains intact), and @SQLRestriction hides it from
        // all queries and joins. The bindings were already dropped by the unbind above; the webhook secret is NOT
        // touched — agents.webhook_auth_secret_id still references it through an FK.
        agent.setDeletedAt(LocalDateTime.now());

        log.info("Soft-deleted agent id={}", id);
    }

    private AgenticTeam resolveTeam(UUID agenticTeamId) {
        if (agenticTeamId == null) {
            return null;
        }
        return agenticTeamRepository.findById(agenticTeamId).orElse(null);
    }

    /**
     * Moving the brain to a transport we cannot push to leaves the agent's channels answering nobody:
     * the chat still takes messages, the trigger still fires, and the reply never comes because there is
     * no recipient. We refuse the move instead — closing someone's live chat as a side effect of an
     * edit would be worse than the refusal.
     */
    private void requireChannelsCanFollowTheType(Agent agent, AgentType type) {
        if (type == agent.getType() || agentDeliveryService.supportsPush(type)) {
            return;
        }
        if (!channelRepository.findByAgentIdAndDeletedAtIsNullOrderByCreatedAtDesc(agent.getId()).isEmpty()) {
            throw new BadRequestStatusException(
                    "Agent of type " + type + " receives no messages: close its channels before switching");
        }
    }

    private void validateWebhookFields(AgentType type, String webhookUrl) {
        if (type == AgentType.WEBHOOK && (webhookUrl == null || webhookUrl.isBlank())) {
            throw new ValidationErrorStatusException("webhookUrl", "Webhook url is required when type is WEBHOOK");
        }
    }

    /**
     * The webhook's auth header is stored envelope-encrypted in {@code secrets} (see
     * {@link #WEBHOOK_AUTH_SECRET_ENTITY}). The semantics repeats the former plaintext set:
     * {@code null} or blank clears it (the secrets row is deleted), anything else is a store/update.
     */
    private void applyWebhookAuthHeader(Agent agent, String header) {
        if (header == null || header.isBlank()) {
            if (agent.getWebhookAuthSecretId() != null) {
                UUID secretId = agent.getWebhookAuthSecretId();
                agent.setWebhookAuthSecretId(null);
                agentRepository.saveAndFlush(agent);
                secretRepository.deleteById(secretId);
            }
            return;
        }
        if (agent.getWebhookAuthSecretId() != null) {
            Secret secret = secretRepository.findById(agent.getWebhookAuthSecretId()).orElse(null);
            if (secret != null) {
                secretService.updateValue(secret, agent.getId(), header);
                return;
            }
        }
        Secret secret = secretService.storeValue(WEBHOOK_AUTH_SECRET_ENTITY, agent.getId(), header);
        agent.setWebhookAuthSecretId(secret.getId());
    }

    public record AgentCreateResult(Agent agent, AgenticTeam team, String plaintextKey) {}
}
