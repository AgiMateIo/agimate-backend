package ru.agimate.controlapi.service.runcontext;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.controlapi.connectors.core.ConnectorEnv;
import ru.agimate.controlapi.connectors.core.ConnectorEnvFactory;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.connectors.core.ConnectorRegistry;
import ru.agimate.controlapi.connectors.core.PromptBlockProvider;
import ru.agimate.controlapi.connectors.core.ToolProvider;
import ru.agimate.controlapi.connectors.core.TriggerProvider;
import ru.agimate.controlapi.connectors.core.dto.ConnectorToolSpec;
import ru.agimate.controlapi.connectors.core.dto.ContextDirectives;
import ru.agimate.controlapi.connectors.core.dto.PromptBlock;
import ru.agimate.controlapi.connectors.core.dto.TriggerSpec;
import ru.agimate.controlapi.connectors.core.ConnectionToolMapper;
import ru.agimate.controlapi.controller.agent.dto.AgentSkillWithConnectorsResponse;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.entities.AgentSkill;
import ru.agimate.controlapi.database.entities.AgenticTeam;
import ru.agimate.controlapi.database.entities.Connection;
import ru.agimate.controlapi.database.entities.Connector;
import ru.agimate.controlapi.database.entities.Skill;
import ru.agimate.controlapi.database.entities.AgentRun;
import ru.agimate.controlapi.connectors.core.InternalConnectorHandler;
import ru.agimate.controlapi.database.repositories.AgentRepository;
import ru.agimate.controlapi.database.repositories.AgentSkillRepository;
import ru.agimate.controlapi.database.repositories.AgenticTeamRepository;
import ru.agimate.controlapi.database.repositories.ChannelRepository;
import ru.agimate.controlapi.database.repositories.ConnectionRepository;
import ru.agimate.controlapi.database.repositories.ConnectionToolRepository;
import ru.agimate.controlapi.database.repositories.ConnectorRepository;
import ru.agimate.controlapi.database.repositories.SkillRepository;
import ru.agimate.controlapi.database.repositories.AgentRunRepository;
import ru.agimate.controlapi.service.AgentSkillService;
import ru.agimate.controlapi.service.channel.InboundTextResolver;
import ru.agimate.controlapi.service.channel.handler.ChannelHandler;
import ru.agimate.controlapi.service.channel.handler.ChannelHandlerRegistry;
import ru.agimate.controlapi.service.channel.handler.dto.InboundMessage;
import ru.agimate.controlapi.service.seed.PromptTexts;
import ru.agimate.controlapi.service.channel.handler.dto.Part;
import ru.agimate.controlapi.service.trigger.Channels;
import ru.agimate.controlapi.service.trigger.ChannelsCodec;
import ru.agimate.controlapi.service.trigger.Trigger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Assembly of a run's context for {@code GetRunContext}: the policy ({@link ContextSpec}) is chosen
 * from the route's channel snapshot ({@code agent_runs.channels}), the blocks are collected from the
 * agent's spec, the {@link PromptBlockProvider} connectors, the team and the skills; the tools are
 * scoped by skills. The worker receives finished, ordered blocks and merely renders them.
 *
 * <p>The order of the system blocks is part of the contract (stable ones first, friendly to the
 * prompt cache): agent → the agent's instructions → connector blocks → team → skills → skill bodies
 * (in a dialogue all of them, in a trigger run the ones matching the event's connector) → trigger
 * guidance. The run's main prompt is the last user block.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RunContextService {

    // The constants below are the English source and the fallback: on an installation with another
    // language the block comes from seed/texts/<lang>/prompt.properties, under the PromptTexts.RUN_* keys.

    /** Trigger-path guidance (trusted instructions): autonomous handling of events, not a dialogue. */
    static final String TRIGGER_GUIDANCE =
            "- This is autonomous handling of external events, not a conversation. If the events "
            + "require nothing of you, you do not have to answer; a very short reply is fine, for "
            + "example: \"Decided to ignore, no action required\".\n"
            + "- Every tool call must be justified: call a tool only when the event genuinely "
            + "requires action, and briefly state why you are calling it.\n"
            + "- The only acceptable result is a verifiable artefact: a file or task id from a tool "
            + "result, a call that actually happened. If the tool you need does not exist or the call "
            + "failed, record a blocker and stop. Never report work that did not happen, and never "
            + "invent file ids.";

    /**
     * The rule for calling tools — added whenever the run has any tools. Deliberately states the rule
     * without quoting any imitation pattern: showing the model the exact text of a forbidden «call»
     * hands it a template for the very thing being forbidden.
     */
    static final String TOOL_CALL_GUIDANCE =
            "Call tools only through the structural tool-calling API. A tool call written as reply "
            + "text is never executed — the user just sees the text, and the work does not happen.";

    /**
     * The answer's attach convention — added only in DIALOGUE runs whose prompt channel supports
     * attachments ({@code ChannelHandler.supportsOutboundAttachments()}); otherwise the agent would
     * attach a file the channel silently fails to deliver.
     */
    static final String ATTACHMENT_GUIDANCE =
            "To attach a file to your reply to the user, put the marker [[attach:agf_...]] with the "
            + "file id (format agf_<uuid>) into your reply text. The id comes either from a tool "
            + "result (the file.id field) or from the description of a file the user uploaded (the "
            + "line \"Uploaded file description ... id: agf_...\"). The marker is stripped from the "
            + "text and the file is delivered to the channel as an attachment (image/video/document, "
            + "by file type). Do not invent ids: use only the ones you received in this conversation.";

    /** Deterministic serialisation of an event (sorted keys) — the same block whatever the map's order. */
    private static final ObjectMapper EVENT_MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private final AgentRunRepository agentRunRepository;
    private final AgentRepository agentRepository;
    private final AgenticTeamRepository agenticTeamRepository;
    private final AgentSkillRepository agentSkillRepository;
    private final AgentSkillService agentSkillService;
    private final SkillRepository skillRepository;
    private final ConnectionRepository connectionRepository;
    private final ConnectorRepository connectorRepository;
    private final ConnectionToolRepository connectionToolRepository;
    private final ConnectorRegistry connectorRegistry;
    private final ConnectorEnvFactory envFactory;
    private final ChannelRepository channelRepository;
    private final ChannelHandlerRegistry channelHandlerRegistry;
    private final InboundTextResolver inboundTextResolver;
    private final RunHistoryAssembler historyAssembler;
    private final PromptTexts promptTexts;

    public RunContextView build(UUID agentId, UUID triggerId) {
        AgentRun run = agentRunRepository.findById(triggerId)
                .orElseThrow(() -> new NotFoundStatusException("Run not found: " + triggerId));
        Agent agent = run.getAgent();
        if (!agent.getId().equals(agentId)) {
            throw new BadRequestStatusException("Run " + triggerId + " does not belong to agent " + agentId);
        }
        if (!agent.isEnabled()) {
            throw new BadRequestStatusException("Agent is disabled: " + agentId);
        }

        Channels channels = ChannelsCodec.fromMap(run.getChannels());
        ContextSpec spec = channels != null && channels.prompt() != null
                ? ContextSpec.DIALOGUE
                : ContextSpec.SYSTEM_TRIGGER;
        Trigger trigger = Trigger.fromLog(run.getTriggerLog());
        // Directives come only from the connector code's static declaration (the registry); dynamic
        // triggers (connection_triggers) and the payload never reach here — an unfamiliar name = the base preset.
        EffectiveContext effective = EffectiveContext.of(spec, declaredDirectives(trigger));

        // Skills: the tools come from ALL of the agent's skills — the content of a task delegated through a
        // trigger has nothing to do with the event's connector (a task from the board may require media). The
        // bodies: in a dialogue all of them (skills define behaviour there too), in a trigger run they are
        // scoped by the event's connector.
        // Only satisfied skills reach the agent: a skill whose connector has no reachable instance would
        // promise tools that are not in the context. The same map carries the instances themselves — the
        // gate is «this connection», not «any connection of that code».
        Map<UUID, Set<UUID>> satisfied = agentSkillService.satisfiedSkillInstances(agentId);
        List<AgentSkillWithConnectorsResponse> listed = listedSkills(agentId).stream()
                .filter(skill -> satisfied.containsKey(skill.skillId()))
                .toList();
        List<AgentSkillWithConnectorsResponse> scoped = switch (spec.skillBodies()) {
            case ALL -> listed;
            case MATCHED -> matchedSkills(listed, trigger);
        };
        Set<UUID> requiredConnections = new LinkedHashSet<>();
        if (effective.skillTools()) {
            listed.forEach(skill -> requiredConnections.addAll(satisfied.getOrDefault(skill.skillId(), Set.of())));
        }

        List<Connection> connections = connectionRepository.findActiveBoundToAgent(agentId);
        UUID promptChannelId = channels != null && channels.prompt() != null
                ? channels.prompt().channelId() : null;
        UUID promptSessionId = channels != null && channels.prompt() != null
                ? channels.prompt().sessionId() : null;
        // A channel that brings its own tools (the IDE connector) mixes the prompt channel's connector in past
        // the skill gate — «the channel brings tools», for as long as the conversation comes from that channel.
        // It returns that channel's connection so its tools are listed session-aware (session-scoped MCP from the IDE).
        UUID sessionAwareConnectionId = addPromptChannelTools(promptChannelId, requiredConnections);
        // ownConnectionTools: the event's connection (that one specifically, not every connection of its code —
        // INSTANCE) enters the selection past the skill gate.
        UUID ownConnectionId = effective.ownConnectionTools()
                ? tryParseUuid(trigger.connectionId()) : null;

        List<RunTool> tools = collectTools(connections, requiredConnections, ownConnectionId,
                sessionAwareConnectionId, promptSessionId);

        List<RunBlock> systemBlocks = new ArrayList<>();
        List<RunBlock> userBlocks = new ArrayList<>();

        systemBlocks.add(agentBlock(agent));
        if (agent.getInstructions() != null && !agent.getInstructions().isBlank()) {
            systemBlocks.add(RunBlock.trusted("", "agent", agent.getInstructions().strip(), Map.of()));
        }
        collectConnectorBlocks(connections, agent, promptChannelId, promptSessionId, systemBlocks, userBlocks);
        teamBlock(agent).ifPresent(systemBlocks::add);
        if (!listed.isEmpty()) {
            systemBlocks.add(skillsBlock(listed));
        }
        systemBlocks.addAll(skillBodyBlocks(scoped));
        if (!tools.isEmpty()) {
            systemBlocks.add(RunBlock.trusted("tool_guidance", "guidance",
                    promptTexts.get(PromptTexts.RUN_TOOL_CALL_GUIDANCE, TOOL_CALL_GUIDANCE), Map.of()));
        }
        if (effective.triggerGuidance()) {
            systemBlocks.add(RunBlock.trusted("trigger_guidance", "guidance",
                    promptTexts.get(PromptTexts.RUN_TRIGGER_GUIDANCE, TRIGGER_GUIDANCE), Map.of()));
        }
        if (spec == ContextSpec.DIALOGUE && promptChannelSupportsAttachments(channels)) {
            systemBlocks.add(RunBlock.trusted("attachment_guidance", "guidance",
                    promptTexts.get(PromptTexts.RUN_ATTACHMENT_GUIDANCE, ATTACHMENT_GUIDANCE), Map.of()));
        }

        // The run's main prompt is the last user block; the inbound attachments go separately (multimodality),
        // and we resolve the message once: text → the block, parts → RunContextView.
        List<InboundPart> inboundParts = List.of();
        if (spec == ContextSpec.DIALOGUE) {
            Optional<InboundMessage> inbound = inboundTextResolver.resolve(channels.prompt().channelId(), trigger);
            userBlocks.add(dialoguePromptBlock(inbound, trigger));
            inboundParts = inboundParts(inbound);
        } else {
            if (effective.guidance() != null) {
                String guidance = promptTexts.triggerGuidance(
                        trigger.connectorCode(), trigger.name(), effective.guidance());
                userBlocks.add(RunBlock.trusted("event_guidance",
                        "connector:" + trigger.connectorCode(), guidance, Map.of()));
            }
            userBlocks.add(triggerMainBlock(effective, trigger));
        }

        // The channel's session, not the run's: a trigger run has one too now, but «the agent
        // remembers the previous events of its connection» is a separate decision, and it is not
        // this one (docs/decisions/agent-sessions.md, historyScope).
        List<RunHistoryMessage> history = historyAssembler.assemble(
                Channels.sessionIdOf(channels), effective.historyLimit(), effective.historyParts());
        log.debug("run context agent={} trigger={} spec={} blocks={}/{} tools={} history={} parts={}",
                agentId, triggerId, spec, systemBlocks.size(), userBlocks.size(), tools.size(),
                history.size(), inboundParts.size());
        return new RunContextView(List.copyOf(systemBlocks), List.copyOf(userBlocks), tools, history,
                inboundParts);
    }

    // ===== Skills =====

    private List<AgentSkillWithConnectorsResponse> listedSkills(UUID agentId) {
        List<UUID> skillIds = agentSkillRepository.findByAgentId(agentId).stream()
                .map(AgentSkill::getSkillId)
                .toList();
        Map<UUID, AgentSkillWithConnectorsResponse> resolved = agentSkillService.resolveSkillsById(skillIds);
        return skillIds.stream().map(resolved::get).filter(Objects::nonNull).toList();
    }

    /** A skill matches the trigger when its connector_codes contain the event's connectorCode. */
    private static List<AgentSkillWithConnectorsResponse> matchedSkills(
            List<AgentSkillWithConnectorsResponse> skills, Trigger trigger) {
        return skills.stream()
                .filter(s -> s.connectorCodes().contains(trigger.connectorCode()))
                .toList();
    }

    private List<RunBlock> skillBodyBlocks(List<AgentSkillWithConnectorsResponse> scoped) {
        List<RunBlock> blocks = new ArrayList<>();
        for (AgentSkillWithConnectorsResponse ref : scoped) {
            Skill skill = skillRepository.findByIdNotDeleted(ref.skillId()).orElse(null);
            if (skill == null || skill.getMdContent() == null || skill.getMdContent().isBlank()) {
                continue;
            }
            Map<String, String> attrs = skill.getName() == null || skill.getName().isBlank()
                    ? Map.of()
                    : Map.of("name", skill.getName());
            blocks.add(RunBlock.trusted("skill", "skill", skill.getMdContent().strip(), attrs));
        }
        return blocks;
    }

    private static RunBlock skillsBlock(List<AgentSkillWithConnectorsResponse> skills) {
        List<String> lines = new ArrayList<>();
        for (AgentSkillWithConnectorsResponse s : skills) {
            lines.add("- skill_id: " + s.skillId());
            if (s.skillName() != null && !s.skillName().isBlank()) {
                lines.add("  name: " + s.skillName());
            }
            if (s.description() != null && !s.description().isBlank()) {
                lines.add("  description: " + s.description());
            }
            if (!s.connectorCodes().isEmpty()) {
                lines.add("  connector_codes: " + String.join(", ", s.connectorCodes()));
            }
        }
        return RunBlock.trusted("skills", "skill", String.join("\n", lines), Map.of());
    }

    // ===== Agent / team =====

    private static RunBlock agentBlock(Agent agent) {
        List<String> lines = new ArrayList<>();
        lines.add("- id: " + agent.getId());
        if (agent.getName() != null && !agent.getName().isBlank()) {
            lines.add("- name: " + agent.getName());
        }
        if (agent.getType() != null) {
            lines.add("- type: " + agent.getType().name());
        }
        if (agent.getAgenticTeamId() != null) {
            lines.add("- team_id: " + agent.getAgenticTeamId());
        }
        return RunBlock.trusted("agent", "agent", String.join("\n", lines), Map.of());
    }

    private Optional<RunBlock> teamBlock(Agent agent) {
        if (agent.getAgenticTeamId() == null) {
            return Optional.empty();
        }
        AgenticTeam team = agenticTeamRepository.findById(agent.getAgenticTeamId()).orElse(null);
        if (team == null) {
            return Optional.empty();
        }
        List<String> lines = new ArrayList<>();
        lines.add("- id: " + team.getId());
        if (team.getName() != null && !team.getName().isBlank()) {
            lines.add("- name: " + team.getName());
        }
        if (team.getDescription() != null && !team.getDescription().isBlank()) {
            lines.add("- description: " + team.getDescription());
        }
        List<Agent> members = agentRepository.findByUserIdAndAgenticTeamId(team.getUserId(), team.getId());
        if (!members.isEmpty()) {
            lines.add("Members:");
            for (Agent m : members) {
                StringBuilder line = new StringBuilder("- pub_id=").append(m.getId());
                if (m.getName() != null && !m.getName().isBlank()) {
                    line.append(", name=").append(m.getName());
                }
                if (m.getDescription() != null && !m.getDescription().isBlank()) {
                    line.append(", description=").append(m.getDescription());
                }
                lines.add(line.toString());
            }
        }
        return Optional.of(RunBlock.trusted("team", "team", String.join("\n", lines), Map.of()));
    }

    // ===== Connector blocks =====

    /**
     * Blocks of the {@link PromptBlockProvider} connectors, over the active bound connections. One
     * provider's failure does not bring the context down — the run goes out without its blocks (a
     * warning in the log). Ephemeral for user blocks is derived from {@code stable}: a volatile user
     * block (memory notes) changes every run and is not persisted into history.
     *
     * <p>{@code promptSessionId} is the same session-aware addressing the tools use: a block may
     * depend on the conversation's session (ACP reports the root of the open IDE project).
     */
    private void collectConnectorBlocks(List<Connection> connections, Agent agent, UUID promptChannelId,
                                        UUID promptSessionId,
                                        List<RunBlock> systemBlocks, List<RunBlock> userBlocks) {
        for (Connection connection : connections) {
            PromptBlockProvider provider = connectorRegistry
                    .findCapability(connection.getConnectorCode(), PromptBlockProvider.class)
                    .orElse(null);
            if (provider == null) {
                continue;
            }
            ConnectorEnv env = envFactory.internal(connection.getId().toString(), agent.getUserId(),
                    agent.getId(), null, promptChannelId, promptSessionId);
            List<PromptBlock> blocks;
            try {
                blocks = provider.promptBlocks(env);
            } catch (ConnectorException e) {
                log.warn("promptBlocks failed for {}: {}", connection.getConnectorCode(), e.getMessage());
                continue;
            }
            String source = "connector:" + connection.getConnectorCode();
            for (PromptBlock block : blocks) {
                if (block.placement() == PromptBlock.Placement.SYSTEM) {
                    systemBlocks.add(new RunBlock(block.name(), source, block.content(), block.attrs(),
                            true, false));
                } else {
                    userBlocks.add(new RunBlock(block.name(), source, block.content(), block.attrs(),
                            true, !block.stable()));
                }
            }
        }
    }

    // ===== Main prompt =====

    /** Whether the prompt channel's handler can deliver attachments from an answer ({@code [[attach:…]]}). */
    private boolean promptChannelSupportsAttachments(Channels channels) {
        if (channels == null || channels.prompt() == null) {
            return false;
        }
        return channelRepository.findByIdAndDeletedAtIsNull(channels.prompt().channelId())
                .flatMap(channel -> channelHandlerRegistry.find(channel.getChannelHandler()))
                .map(ChannelHandler::supportsOutboundAttachments)
                .orElse(false);
    }

    /** Inbound attachments → {@link InboundPart} references (only image/video/audio/file reach the context). */
    private RunBlock dialoguePromptBlock(Optional<InboundMessage> inbound, Trigger trigger) {
        return inbound.map(InboundMessage::text)
                .filter(text -> text != null && !text.isBlank())
                .map(text -> RunBlock.trusted("", "user", text, Map.of()))
                .orElseGet(() -> {
                    log.warn("Prompt channel unusable for trigger {} — falling back to event block",
                            trigger.id());
                    return eventBlock(trigger);
                });
    }

    /**
     * The event's main block, per {@link EffectiveContext#presentation()}: {@code PROMPT} means
     * trusted text from {@code data[promptParam]} (declarable by internal connectors only, guarded at
     * bootstrap; the text is authored by the agent or the platform), and empty or non-string falls
     * back to the untrusted event.
     */
    private static List<InboundPart> inboundParts(Optional<InboundMessage> inbound) {
        return inbound.map(m -> m.parts().stream()
                        .map(p -> new InboundPart(p.storageRef(), p.type(), p.mime(), p.size(), partName(p)))
                        .toList())
                .orElse(List.of());
    }

    private static String partName(Part part) {
        Object name = part.meta() != null ? part.meta().get("name") : null;
        return name != null ? name.toString() : "";
    }

    /** The trigger's static directives from the registry ({@code null} — undeclared or a dynamic trigger). */
    private static RunBlock triggerMainBlock(EffectiveContext effective, Trigger trigger) {
        if (effective.presentation() != ContextDirectives.Presentation.PROMPT) {
            return eventBlock(trigger);
        }
        Object raw = trigger.data() != null && effective.promptParam() != null
                ? trigger.data().get(effective.promptParam()) : null;
        if (raw instanceof String text && !text.isBlank()) {
            Map<String, String> attrs = new LinkedHashMap<>();
            attrs.put("connector", trigger.connectorCode());
            attrs.put("name", trigger.name());
            return RunBlock.trusted("trigger_prompt", "connector:" + trigger.connectorCode(),
                    text.strip(), attrs);
        }
        log.warn("PROMPT trigger {}.{} has no usable '{}' in data — falling back to event block",
                trigger.connectorCode(), trigger.name(), effective.promptParam());
        return eventBlock(trigger);
    }

    /** The event as data: an untrusted block, with the wrapper and preamble applied by the worker's renderer. */
    private ContextDirectives declaredDirectives(Trigger trigger) {
        return connectorRegistry.findCapability(trigger.connectorCode(), TriggerProvider.class)
                .map(TriggerProvider::getTriggers)
                .map(triggers -> triggers.get(trigger.name()))
                .map(TriggerSpec::context)
                .orElse(null);
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

    /**
     * If the prompt channel brings its own tools ({@link ChannelHandler#contributesPromptTools}), its
     * connection is added to {@code requiredConnections} — {@link #collectTools} then picks up that
     * binding's tools regardless of the agent's skills.
     *
     * @return the connection of that channel (a session-aware listing), or {@code null}
     */
    private static RunBlock eventBlock(Trigger trigger) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("connectorCode", trigger.connectorCode());
        event.put("connectionId", trigger.connectionId());
        event.put("name", trigger.name());
        event.put("id", trigger.id());
        event.put("data", trigger.data());
        event.put("occurredAt", trigger.occurredAt());
        String content;
        try {
            content = EVENT_MAPPER.writeValueAsString(event);
        } catch (Exception e) {
            content = String.valueOf(event);
        }
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("connector", trigger.connectorCode());
        attrs.put("name", trigger.name());
        return new RunBlock("event", "connector:" + trigger.connectorCode(), content, attrs, false, false);
    }


    // ===== Tools =====

    /**
     * Tools of the connections the scoped skills point at, plus
     * {@code ownConnectionId} (the event's connection under {@code ownConnectionTools} — addressed
     * directly, bypassing the skill gate). For {@code sessionAwareConnectionId} (the connection of a
     * prompt channel that brings tools) the STATIC listing gets an env carrying
     * {@code promptSessionId}, so the connector can return session-scoped tools (MCP from the IDE).
     *
     * <p>A connector with {@link ToolProvider#sessionScopedTools()} enters the selection through that
     * connection only: a skill declaring it as required still gates on the connection being there,
     * but its tools belong to the live session, and elsewhere they would only be schemas that always
     * fail.
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
     * The instance's namespace for the LLM-facing tool name ({@code {namespace}.{name}}): external
     * instances → {@code full_code}; internal mode rows → {@code connector_code}. «Internal vs
     * external» is knowledge of the registry (the handler's type), not a field on the connection.
     */
    private List<RunTool> collectTools(List<Connection> connections, Set<UUID> requiredConnections,
                                       UUID ownConnectionId, UUID sessionAwareConnectionId,
                                       UUID promptSessionId) {
        List<RunTool> tools = new ArrayList<>();
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
            Map<String, ConnectorToolSpec> specs = switch (connector.getDefinitionBinding()) {
                case STATIC -> connectorRegistry
                        .findCapability(connection.getConnectorCode(), ToolProvider.class)
                        .filter(p -> ownSession || !p.sessionScopedTools())
                        .map(p -> p.getTools(listingEnv))
                        .orElse(Map.of());
                case DYNAMIC -> dynamicTools(connection.getId());
            };
            String namespace = namespaceOf(connection);
            specs.forEach((name, spec) -> tools.add(new RunTool(
                    spec, connection.getConnectorCode(), connection.getId().toString(), namespace)));
        }
        return tools;
    }

    private Map<String, ConnectorToolSpec> dynamicTools(UUID connectionId) {
        Map<String, ConnectorToolSpec> tools = new LinkedHashMap<>();
        connectionToolRepository.findActiveByConnectionId(connectionId)
                .forEach(tool -> tools.put(tool.getName(), ConnectionToolMapper.toSpec(tool)));
        return tools;
    }

    /**
     * The dialogue's text: extracted by the same {@code ChannelHandler.handleInput} as at dispatch
     * ({@link InboundTextResolver}). It falls back to the untrusted event block when the channel or
     * handler is gone or no text could be extracted.
     */
    private String namespaceOf(Connection connection) {
        boolean internal = connectorRegistry.findHandler(connection.getConnectorCode())
                .map(InternalConnectorHandler.class::isInstance)
                .orElse(false);
        String ns = internal ? connection.getConnectorCode() : connection.getFullCode();
        return ns == null ? "" : ns;
    }
}
