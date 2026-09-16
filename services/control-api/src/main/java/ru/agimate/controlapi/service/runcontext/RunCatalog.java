package ru.agimate.controlapi.service.runcontext;

import ru.agimate.controlapi.connectors.core.dto.ToolAudience;
import ru.agimate.controlapi.service.tool.ToolDefinitionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.agentworker.ToolNames;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.controlapi.connectors.core.ConnectorEnv;
import ru.agimate.controlapi.connectors.core.ConnectorEnvFactory;
import ru.agimate.controlapi.connectors.core.ConnectorRegistry;
import ru.agimate.controlapi.connectors.core.InternalConnectorHandler;
import ru.agimate.controlapi.connectors.core.ToolProvider;
import ru.agimate.controlapi.connectors.core.TriggerProvider;
import ru.agimate.controlapi.connectors.core.dto.ConnectorToolSpec;
import ru.agimate.controlapi.connectors.core.dto.TriggerSpec;
import ru.agimate.controlapi.controller.agent.dto.AgentSkillWithConnectorsResponse;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.entities.AgentRun;
import ru.agimate.controlapi.database.entities.AgentSkill;
import ru.agimate.controlapi.database.entities.Connection;
import ru.agimate.controlapi.database.entities.Connector;
import ru.agimate.controlapi.database.entities.Skill;
import ru.agimate.controlapi.database.enums.Disclosure;
import ru.agimate.controlapi.database.repositories.AgentRepository;
import ru.agimate.controlapi.database.repositories.AgentRunRepository;
import ru.agimate.controlapi.database.repositories.AgentSessionRepository;
import ru.agimate.controlapi.database.repositories.AgentSkillRepository;
import ru.agimate.controlapi.database.repositories.ChannelRepository;
import ru.agimate.controlapi.database.repositories.ConnectionRepository;
import ru.agimate.controlapi.database.repositories.ConnectorRepository;
import ru.agimate.controlapi.database.repositories.SkillRepository;
import ru.agimate.controlapi.service.AgentSkillService;
import ru.agimate.controlapi.service.AgentSkillService.WithheldSkill;
import ru.agimate.controlapi.service.channel.handler.ChannelHandler;
import ru.agimate.controlapi.service.channel.handler.ChannelHandlerRegistry;
import ru.agimate.controlapi.service.delivery.DetachedToolResultDelivery;
import ru.agimate.controlapi.service.trigger.Channels;
import ru.agimate.controlapi.service.trigger.ChannelsCodec;
import ru.agimate.controlapi.service.trigger.Trigger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * What a run may use: its satisfied skills and the tools of the connections they point at, past the
 * binding gate. One reader was {@code RunContextService}; progressive disclosure adds a second — the
 * loader tools ({@code load_tools}, {@code load_skill}) recompute the same catalogue by
 * {@code env.runId} inside the call, so the worker cannot ask for a schema the agent is not bound to.
 * The scope is the backend's to decide, and it is decided by one piece of code.
 *
 * <p>Two entry points: {@link #forRun} derives the policy from the run's route (the
 * {@link ContextSpec} preset ⊕ the trigger's directives) and adds the event's own connection and the
 * prompt channel's tools; {@link #forAgent} is the coarser agent-level view for a call outside a run
 * (an MCP client, the manage listing) — skill tools only.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RunCatalog {

    /** Codes of the disclosing connectors — each switches one axis on for the run it is bound to. */
    public static final String SKILL_LOADER = "skill-loader";
    public static final String TOOL_LOADER = "tool-loader";
    /** Code of the subagents connector — the one a subagent's own run is denied. */
    public static final String SUBAGENTS = "subagents";

    /**
     * The catalogue of one run, or of an agent outside a run.
     *
     * @param spec           the route preset; {@code null} outside a run
     * @param effective      the preset ⊕ the trigger's directives; {@code null} outside a run
     * @param trigger        the run's event; {@code null} outside a run
     * @param channels       the run's channel snapshot; {@code null} outside a run or for a direct run
     * @param skills         the agent's satisfied skills, in binding order
     * @param withheld       the skills the gate held back, with the reason — the catalogue block says
     *                       so; deliberately a second field rather than a state on {@code skills},
     *                       because every other reader of {@code skills} (the loader tools included)
     *                       means «what the agent may use», and a forgotten filter there would hand
     *                       out the body of a withheld skill
     * @param connections    the agent's active bound connections (for the prompt-block providers)
     * @param tools          the tools in scope, full specs, {@link RunTool#disclosure} = the declared axis
     * @param skillsOnDemand a {@code skill-loader} connection is in scope: lazy bodies may be withheld
     * @param toolsOnDemand  a {@code tool-loader} connection is in scope: lazy schemas may be withheld
     */
    public record Catalog(
            ContextSpec spec,
            EffectiveContext effective,
            Trigger trigger,
            Channels channels,
            List<AgentSkillWithConnectorsResponse> skills,
            List<WithheldSkill> withheld,
            List<Connection> connections,
            List<RunTool> tools,
            boolean skillsOnDemand,
            boolean toolsOnDemand
    ) {

        /** The tool the model knows under {@code llmName}, or {@code null}. */
        public RunTool tool(String llmName) {
            return tools.stream().filter(t -> t.llmName().equals(llmName)).findFirst().orElse(null);
        }

        /** The skill bound under {@code name}, or {@code null}. */
        public AgentSkillWithConnectorsResponse skill(String name) {
            return skills.stream().filter(s -> name.equals(s.skillName())).findFirst().orElse(null);
        }
    }

    private final AgentRunRepository agentRunRepository;
    private final AgentSessionRepository agentSessionRepository;
    private final AgentRepository agentRepository;
    private final AgentSkillRepository agentSkillRepository;
    private final AgentSkillService agentSkillService;
    private final SkillRepository skillRepository;
    private final ConnectionRepository connectionRepository;
    private final ConnectorRepository connectorRepository;
    private final ToolDefinitionService toolDefinitionService;
    private final ConnectorRegistry connectorRegistry;
    private final ConnectorEnvFactory envFactory;
    private final ChannelRepository channelRepository;
    private final ChannelHandlerRegistry channelHandlerRegistry;

    /**
     * The run's catalogue. Loads the run itself: a loader tool runs outside any transaction, and a
     * lazily fetched {@code run.agent} handed in from there would have no session to load from.
     */
    public Catalog forRun(UUID agentId, UUID runId) {
        AgentRun run = agentRunRepository.findById(runId)
                .orElseThrow(() -> new NotFoundStatusException("Run not found: " + runId));
        Agent agent = run.getAgent();
        if (!agent.getId().equals(agentId)) {
            throw new BadRequestStatusException("Run " + runId + " does not belong to agent " + agentId);
        }
        if (!agent.isEnabled()) {
            throw new BadRequestStatusException("Agent is disabled: " + agentId);
        }

        Channels channels = ChannelsCodec.fromMap(run.getChannels());
        Trigger trigger = Trigger.fromLog(run.getTriggerLog());
        // Directives come only from the connector code's static declaration (the registry); dynamic
        // triggers (connection_triggers) and the payload never reach here — an unfamiliar name = the base preset.
        TriggerSpec declared = declaredSpec(trigger);
        ContextSpec spec = presetOf(channels, trigger, declared);
        EffectiveContext effective = EffectiveContext.of(spec, declared != null ? declared.context() : null);

        List<Connection> connections = connectionRepository.findActiveBoundToAgent(agentId);
        Scope scope = skillScope(agentId, effective.skillTools());
        if (isSubagentRun(run)) {
            scope = withoutSubagents(scope, connections);
        }
        UUID promptChannelId = channels != null && channels.prompt() != null ? channels.prompt().channelId() : null;
        UUID promptSessionId = channels != null && channels.prompt() != null ? channels.prompt().sessionId() : null;
        // A channel that brings its own tools (the IDE connector) mixes the prompt channel's connector in past
        // the skill gate — «the channel brings tools», for as long as the conversation comes from that channel.
        // It returns that channel's connection so its tools are listed session-aware (session-scoped MCP from the IDE).
        UUID sessionAwareConnectionId = addPromptChannelTools(promptChannelId, scope.requiredConnections());
        // ownConnectionTools: the event's connection (that one specifically, not every connection of its code —
        // INSTANCE) enters the selection past the skill gate.
        UUID ownConnectionId = effective.ownConnectionTools() ? tryParseUuid(trigger.connectionId()) : null;

        List<RunTool> tools = collectTools(connections, scope.requiredConnections(), ownConnectionId,
                sessionAwareConnectionId, promptSessionId);
        return new Catalog(spec, effective, trigger, channels, scope.skills(), scope.withheld(), connections, tools,
                onDemand(connections, scope.requiredConnections(), SKILL_LOADER),
                onDemand(connections, scope.requiredConnections(), TOOL_LOADER));
    }

    /** The agent-level catalogue for a call outside a run: the tools of the satisfied skills, nothing route-specific. */
    public Catalog forAgent(UUID agentId) {
        Agent agent = agentRepository.findById(agentId)
                .orElseThrow(() -> new NotFoundStatusException("Agent not found: " + agentId));
        Scope scope = skillScope(agent.getId(), true);
        List<Connection> connections = connectionRepository.findActiveBoundToAgent(agentId);
        List<RunTool> tools = collectTools(connections, scope.requiredConnections(), null, null, null);
        return new Catalog(null, null, null, null, scope.skills(), scope.withheld(), connections, tools,
                onDemand(connections, scope.requiredConnections(), SKILL_LOADER),
                onDemand(connections, scope.requiredConnections(), TOOL_LOADER));
    }

    /** The body of a bound skill by its catalogue entry; {@code null} when the row is gone or empty. */
    public String skillBody(AgentSkillWithConnectorsResponse skill) {
        return skillRepository.findByIdNotDeleted(skill.skillId())
                .map(Skill::getMdContent)
                .filter(body -> !body.isBlank())
                .orElse(null);
    }

    /** The body of the bound skill named {@code name} — the form the connector layer uses, no DTO crossing. */
    public Optional<String> skillBody(Catalog catalog, String name) {
        AgentSkillWithConnectorsResponse skill = catalog.skill(name);
        return skill == null ? Optional.empty() : Optional.ofNullable(skillBody(skill));
    }

    /** Names of the bound skills, for a miss message. */
    public List<String> skillNames(Catalog catalog) {
        return catalog.skills().stream().map(AgentSkillWithConnectorsResponse::skillName).filter(Objects::nonNull).toList();
    }

    // ===== Skills =====

    /** The satisfied skills, the connections their tools come from, and what the gate held back. */
    private record Scope(List<AgentSkillWithConnectorsResponse> skills, List<WithheldSkill> withheld,
                         Set<UUID> requiredConnections) {}

    /**
     * Only satisfied skills reach the agent: a skill whose connector has no usable instance would
     * promise tools that are not in the context. Its name and the reason travel anyway — as a line in
     * the catalogue, never a body. The same map carries the instances themselves — the gate is «this
     * connection», not «any connection of that code». The tools come from ALL of the
     * agent's skills — the content of a task delegated through a trigger has nothing to do with the
     * event's connector (a task from the board may require media).
     */
    private Scope skillScope(UUID agentId, boolean skillTools) {
        AgentSkillService.SkillGate gate = agentSkillService.gate(agentId);
        Map<UUID, Set<UUID>> satisfied = gate.satisfied();
        List<AgentSkill> bindings = agentSkillRepository.findByAgentId(agentId);
        Map<UUID, AgentSkillWithConnectorsResponse> resolved = agentSkillService.resolveSkills(bindings);
        List<AgentSkillWithConnectorsResponse> listed = bindings.stream()
                .map(binding -> resolved.get(binding.getSkillId()))
                .filter(Objects::nonNull)
                .filter(skill -> satisfied.containsKey(skill.skillId()))
                .toList();
        Set<UUID> required = new LinkedHashSet<>();
        if (skillTools) {
            listed.forEach(skill -> required.addAll(satisfied.getOrDefault(skill.skillId(), Set.of())));
        }
        return new Scope(listed, gate.withheld(), required);
    }

    /** The axis is read only where a disclosing connection is in scope; elsewhere everything is EAGER, as before. */
    private static boolean onDemand(List<Connection> connections, Set<UUID> required, String loaderCode) {
        return connections.stream()
                .anyMatch(c -> loaderCode.equals(c.getConnectorCode()) && required.contains(c.getId()));
    }

    /** The trigger's static declaration from the registry ({@code null} — undeclared or a dynamic trigger). */
    private TriggerSpec declaredSpec(Trigger trigger) {
        return connectorRegistry.findCapability(trigger.connectorCode(), TriggerProvider.class)
                .map(TriggerProvider::getTriggers)
                .map(triggers -> triggers.get(trigger.name()))
                .orElse(null);
    }

    /**
     * The route preset. A detached tool's result is a platform trigger with no declaration of its own
     * (its connector code is the tool's), so it is named here; every other event that carries a
     * conversation on says so in its {@link TriggerSpec}. Either way it needs a conversation to carry
     * on — without one it is an autonomous event like any other.
     */
    static ContextSpec presetOf(Channels channels, Trigger trigger, TriggerSpec declared) {
        if (channels != null && channels.prompt() != null) {
            return ContextSpec.DIALOGUE;
        }
        boolean carriesOn = DetachedToolResultDelivery.TRIGGER_NAME.equals(trigger.name())
                || (declared != null && declared.continuesConversation());
        return carriesOn && Channels.sessionIdOf(channels) != null
                ? ContextSpec.DIALOGUE_EVENT
                : ContextSpec.SYSTEM_TRIGGER;
    }

    // ===== Run role =====

    /** A subagent's run: its session works for another conversation. */
    private boolean isSubagentRun(AgentRun run) {
        return agentSessionRepository.findById(run.getSessionId())
                .map(session -> session.getParentSessionId() != null)
                .orElse(false);
    }

    /**
     * What a subagent does not get: the subagents connector itself — its tools (depth 1) and the skills
     * that require it, whose bodies would describe tools the run does not have. The one place a run's
     * role narrows the catalogue; further restrictions for subagents belong here too.
     */
    private static Scope withoutSubagents(Scope scope, List<Connection> connections) {
        Set<UUID> required = new LinkedHashSet<>(scope.requiredConnections());
        connections.stream()
                .filter(c -> SUBAGENTS.equals(c.getConnectorCode()))
                .forEach(c -> required.remove(c.getId()));
        List<AgentSkillWithConnectorsResponse> skills = scope.skills().stream()
                .filter(skill -> skill.connectorCodes() == null || !skill.connectorCodes().contains(SUBAGENTS))
                .toList();
        return new Scope(skills, scope.withheld(), required);
    }

    // ===== Tools =====

    /**
     * If the prompt channel brings its own tools ({@link ChannelHandler#contributesPromptTools}), its
     * connection is added to {@code requiredConnections} — {@link #collectTools} then picks up that
     * binding's tools regardless of the agent's skills.
     *
     * @return the connection of that channel (a session-aware listing), or {@code null}
     */
    private UUID addPromptChannelTools(UUID promptChannelId, Set<UUID> requiredConnections) {
        if (promptChannelId == null) {
            return null;
        }
        return channelRepository.findByIdAndDeletedAtIsNull(promptChannelId)
                .filter(channel -> channelHandlerRegistry.find(channel.getChannelHandler())
                        .filter(ChannelHandler::contributesPromptTools).isPresent())
                .map(channel -> {
                    requiredConnections.add(channel.getConnectionId());
                    return channel.getConnectionId();
                })
                .orElse(null);
    }

    /**
     * Tools of the connections the scoped skills point at, plus
     * {@code ownConnectionId} (the event's connection under {@code ownConnectionTools} — addressed
     * directly, bypassing the skill gate). For {@code sessionAwareConnectionId} (the connection of a
     * prompt channel that brings tools) the STATIC listing gets an env carrying
     * {@code promptSessionId}, so the connector can return session-scoped tools (MCP from the IDE).
     *
     * <p>Deliberately blind to {@link Connection#isUsable()}. The skill gate already drops a skill whose
     * instance is not usable, and it tells the agent why; what enters past that gate — the event's own
     * connection, a prompt channel that brings tools — has no such line, and hiding the tool there
     * would also hide the only thing that can fix it: a dead MCP grant answers the call with a
     * re-authorisation link the agent can pass to the user.
     *
     * <p>A connector with {@link ToolProvider#sessionScopedTools()} enters the selection through that
     * connection only: a skill declaring it as required still gates on the connection being there,
     * but its tools belong to the live session, and elsewhere they would only be schemas that always
     * fail.
     *
     * <p>The declared disclosure axis is resolved here — the tool's own override, else the
     * connector's — and the LLM-facing names are minted in listing order, so the worker's fallback
     * walks the same list the same way.
     */
    private List<RunTool> collectTools(List<Connection> connections, Set<UUID> requiredConnections,
                                       UUID ownConnectionId, UUID sessionAwareConnectionId,
                                       UUID promptSessionId) {
        List<RunTool> tools = new ArrayList<>();
        Set<String> llmNames = new HashSet<>();
        for (Connection connection : connections) {
            if (!requiredConnections.contains(connection.getId())
                    && !connection.getId().equals(ownConnectionId)) {
                continue;
            }
            Connector connector = connectorRepository.findById(connection.getConnectorCode()).orElse(null);
            if (connector == null || connector.getDefinitionBinding() == null) {
                continue;
            }
            boolean ownSession = connection.getId().equals(sessionAwareConnectionId);
            ConnectorEnv listingEnv = ownSession
                    ? envFactory.internal(connection.getId().toString(), null, null, null, null, promptSessionId)
                    : ConnectorEnvFactory.listing(connection.getId());
            boolean sessionOnly = connectorRegistry.findCapability(connection.getConnectorCode(), ToolProvider.class)
                    .map(ToolProvider::sessionScopedTools)
                    .orElse(false);
            Map<String, ConnectorToolSpec> specs = sessionOnly && !ownSession
                    ? Map.of()
                    : toolDefinitionService.getTools(connection, listingEnv, ToolAudience.MODEL);
            String namespace = namespaceOf(connection);
            Disclosure connectorAxis = connector.getDisclosure() != null ? connector.getDisclosure() : Disclosure.EAGER;
            specs.forEach((name, spec) -> {
                String llmName = ToolNames.unique(llmNames, ToolNames.sanitize(
                        (namespace.isBlank() ? connection.getConnectorCode() : namespace) + "." + name));
                llmNames.add(llmName);
                Disclosure axis = spec.disclosure() != null ? spec.disclosure() : connectorAxis;
                tools.add(new RunTool(spec, connection.getConnectorCode(), connection.getId().toString(),
                        namespace, llmName, axis, null));
            });
        }
        return tools;
    }

    /**
     * The instance's namespace for the LLM-facing tool name ({@code {namespace}.{name}}): external
     * instances → {@code full_code}; internal mode rows → {@code connector_code}. «Internal vs
     * external» is knowledge of the registry (the handler's type), not a field on the connection.
     */
    private String namespaceOf(Connection connection) {
        boolean internal = connectorRegistry.findHandler(connection.getConnectorCode())
                .map(InternalConnectorHandler.class::isInstance)
                .orElse(false);
        String ns = internal ? connection.getConnectorCode() : connection.getFullCode();
        return ns == null ? "" : ns;
    }

    private static UUID tryParseUuid(String value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
