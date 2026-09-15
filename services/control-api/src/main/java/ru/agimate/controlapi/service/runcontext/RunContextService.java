package ru.agimate.controlapi.service.runcontext;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.controlapi.connectors.core.ConnectorEnv;
import ru.agimate.controlapi.connectors.core.ConnectorEnvFactory;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.connectors.core.ConnectorRegistry;
import ru.agimate.controlapi.connectors.core.PromptBlockProvider;
import ru.agimate.controlapi.connectors.core.dto.PromptBlock;
import ru.agimate.controlapi.controller.agent.dto.AgentSkillWithConnectorsResponse;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.entities.AgenticTeam;
import ru.agimate.controlapi.database.entities.Connection;
import ru.agimate.controlapi.database.enums.Disclosure;
import ru.agimate.controlapi.database.repositories.AgentRepository;
import ru.agimate.controlapi.database.repositories.AgenticTeamRepository;
import ru.agimate.controlapi.database.repositories.ChannelRepository;
import ru.agimate.controlapi.service.AgentSkillService.WithheldSkill;
import ru.agimate.controlapi.service.channel.InboundTextResolver;
import ru.agimate.controlapi.service.channel.handler.ChannelHandler;
import ru.agimate.controlapi.service.channel.handler.ChannelHandlerRegistry;
import ru.agimate.controlapi.service.channel.handler.dto.InboundMessage;
import ru.agimate.controlapi.service.channel.handler.dto.Part;
import ru.agimate.controlapi.service.seed.PromptTexts;
import ru.agimate.controlapi.service.trigger.Channels;
import ru.agimate.controlapi.service.trigger.Trigger;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Assembly of a run's context for {@code GetRunContext}: the policy ({@link ContextSpec}) is chosen
 * from the route's channel snapshot ({@code agent_runs.channels}), the blocks are collected from the
 * agent's spec, the {@link PromptBlockProvider} connectors, the team and the skills; the tools are
 * scoped by skills ({@link RunCatalog}). The worker receives finished, ordered blocks and merely
 * renders them.
 *
 * <p>The order of the system blocks is part of the contract (stable ones first, friendly to the
 * prompt cache): the agent's instructions → agent → connector blocks → team → skills → skill bodies
 * (in a dialogue all of them, in a trigger run the ones matching the event's connector) → the
 * withheld-skills note (only when the gate held something back) → deferred tools → trigger guidance. The run's main prompt is the last user block.
 *
 * <p>Progressive disclosure ({@code docs/decisions/progressive-disclosure.md}) is decided here, on
 * the wire form: a LAZY tool ships without its schema and with a summary, a LAZY skill without its
 * body — but only when the run has the disclosing connector for that axis, and never for what the
 * history window shows was already disclosed, or what a trigger run needs up front.
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

    /**
     * What an {@code unavailable} entry in the skills catalogue means — emitted only when the gate
     * withheld something. Its own block, like every other rule of behaviour here: inside the
     * catalogue it would sit unindented under the last {@code blocked_by:} line and read as part of
     * that one entry. Deliberately also says when <em>not</em> to bring it up: a standing note about
     * missing configuration turns into an agent that mentions it every turn.
     */
    static final String SKILLS_UNAVAILABLE_GUIDANCE =
            "A skill listed with status: unavailable is not loaded: its instructions and tools are not "
            + "in this context and cannot be used. If the user asks for something such a skill covers, "
            + "say plainly what is missing — the blocked_by line names the connection and the reason — "
            + "instead of attempting the work or inventing a result. Do not raise it unprompted.";

    private final RunCatalog runCatalog;
    private final AgentRepository agentRepository;
    private final AgenticTeamRepository agenticTeamRepository;
    private final ConnectorRegistry connectorRegistry;
    private final ConnectorEnvFactory envFactory;
    private final ChannelRepository channelRepository;
    private final ChannelHandlerRegistry channelHandlerRegistry;
    private final InboundTextResolver inboundTextResolver;
    private final RunHistoryAssembler historyAssembler;
    private final PromptTexts promptTexts;
    private final TriggerBlocks triggerBlocks;

    public RunContextView build(UUID agentId, UUID triggerId) {
        RunCatalog.Catalog catalog = runCatalog.forRun(agentId, triggerId);
        Agent agent = agentRepository.findById(agentId).orElseThrow();
        ContextSpec spec = catalog.spec();
        EffectiveContext effective = catalog.effective();
        Trigger trigger = catalog.trigger();
        Channels channels = catalog.channels();
        List<AgentSkillWithConnectorsResponse> listed = catalog.skills();

        // The channel's session, not the run's: a trigger run has one too now, but «the agent
        // remembers the previous events of its connection» is a separate decision, and it is not
        // this one (docs/decisions/agent-sessions.md, historyScope). Assembled before the tools: the
        // window says what is already disclosed.
        RunHistory history = historyAssembler.assemble(
                Channels.sessionIdOf(channels), effective.historyLimit(), effective.historyParts());
        List<RunTool> tools = wireTools(catalog, history);

        // The bodies: in a dialogue all of them (skills define behaviour there too), in a trigger run
        // they are scoped by the event's connector. With a skill-loader in scope a LAZY body is
        // withheld — except up front, where there is no window to disclose from.
        List<AgentSkillWithConnectorsResponse> scoped = switch (spec.skillBodies()) {
            case ALL -> listed;
            case MATCHED -> matchedSkills(listed, trigger);
        };
        boolean withholdLazyBodies = catalog.skillsOnDemand() && !spec.disclosesUpfront();
        List<AgentSkillWithConnectorsResponse> bodies = withholdLazyBodies
                ? scoped.stream().filter(s -> s.disclosure() != Disclosure.LAZY).toList()
                : scoped;

        UUID promptChannelId = channels != null && channels.prompt() != null ? channels.prompt().channelId() : null;

        List<RunBlock> systemBlocks = new ArrayList<>();
        List<RunBlock> userBlocks = new ArrayList<>();

        // The user's instructions open the prompt: its head carries the most weight, the metadata does not deserve it.
        if (agent.getInstructions() != null && !agent.getInstructions().isBlank()) {
            systemBlocks.add(RunBlock.trusted("", "agent", agent.getInstructions().strip(), Map.of()));
        }
        systemBlocks.add(agentBlock(agent));
        // The conversation's session, not only the prompt's: an event that carries a conversation on (a
        // subagent's report) has no prompt channel, yet its blocks describe that conversation.
        collectConnectorBlocks(catalog.connections(), agent, promptChannelId, Channels.sessionIdOf(channels),
                systemBlocks, userBlocks);
        teamBlock(agent).ifPresent(systemBlocks::add);
        if (!listed.isEmpty() || !catalog.withheld().isEmpty()) {
            systemBlocks.add(skillsBlock(listed, catalog.withheld(), catalog.skillsOnDemand()));
        }
        systemBlocks.addAll(skillBodyBlocks(bodies));
        if (!catalog.withheld().isEmpty()) {
            systemBlocks.add(RunBlock.trusted("skills_unavailable_guidance", "guidance",
                    promptTexts.get(PromptTexts.RUN_SKILLS_UNAVAILABLE_GUIDANCE, SKILLS_UNAVAILABLE_GUIDANCE),
                    Map.of()));
        }
        deferredToolsBlock(tools).ifPresent(systemBlocks::add);
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
            userBlocks.addAll(triggerBlocks.of(trigger, effective));
        }

        RunContextView view = new RunContextView(List.copyOf(systemBlocks), List.copyOf(userBlocks), tools,
                history.messages(), inboundParts);
        if (log.isDebugEnabled()) {
            log.debug("run context agent={} trigger={} spec={} parts={} size: {}",
                    agentId, triggerId, spec, inboundParts.size(), ContextSizeReport.of(view));
        }
        return view;
    }

    // ===== Tools on the wire =====

    /**
     * The wire form of every tool. Without a tool-loader in scope everything is EAGER, as before the
     * axis existed. With one, a LAZY tool ships as a listing line — unless the event's own connector
     * needs it up front (a trigger run has no window to disclose from), or the window shows the model
     * already called or had it described, newest first while the disclosure budget lasts.
     */
    private static List<RunTool> wireTools(RunCatalog.Catalog catalog, RunHistory history) {
        if (!catalog.toolsOnDemand()) {
            return catalog.tools().stream().map(RunTool::eager).toList();
        }
        Set<String> disclosed = new HashSet<>();
        int budget = history.budgetLeft();
        for (String name : history.disclosedTools()) {
            RunTool tool = catalog.tool(name);
            if (tool == null || tool.disclosure() != Disclosure.LAZY) {
                continue; // gone, or eager anyway — costs nothing
            }
            int size = schemaBytes(tool);
            if (size > budget) {
                log.debug("disclosure budget exhausted at {}: {} bytes left", name, budget);
                break;
            }
            budget -= size;
            disclosed.add(name);
        }
        String upfrontConnector = catalog.spec() != null && catalog.spec().disclosesUpfront() && catalog.trigger() != null
                ? catalog.trigger().connectorCode() : null;
        List<RunTool> wire = new ArrayList<>(catalog.tools().size());
        for (RunTool tool : catalog.tools()) {
            boolean lazy = tool.disclosure() == Disclosure.LAZY
                    && !disclosed.contains(tool.llmName())
                    && !tool.connectorCode().equals(upfrontConnector);
            wire.add(lazy ? tool.lazy(Summaries.of(tool.spec().description())) : tool.eager());
        }
        return wire;
    }

    private static int schemaBytes(RunTool tool) {
        return tool.spec().inputSchema() == null ? 0
                : JsonUtils.writeValueAsString(tool.spec().inputSchema()).getBytes(StandardCharsets.UTF_8).length;
    }

    /** The listing of deferred tools: {@code llm_name: summary} per line — the name the model passes to {@code load_tools}. */
    private static Optional<RunBlock> deferredToolsBlock(List<RunTool> tools) {
        List<String> lines = tools.stream()
                .filter(t -> t.disclosure() == Disclosure.LAZY)
                .map(t -> "- " + t.llmName() + (t.summary().isEmpty() ? "" : ": " + t.summary()))
                .toList();
        if (lines.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(RunBlock.trusted("deferred_tools", "guidance", String.join("\n", lines), Map.of()));
    }

    // ===== Skills =====

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
            String body = runCatalog.skillBody(ref);
            if (body == null) {
                continue;
            }
            Map<String, String> attrs = ref.skillName() == null || ref.skillName().isBlank()
                    ? Map.of()
                    : Map.of("name", ref.skillName());
            blocks.add(RunBlock.trusted("skill", "skill", body.strip(), attrs));
        }
        return blocks;
    }

    /**
     * The catalogue of skills. With a skill-loader in scope a LAZY skill is marked as such, so the
     * model knows its body is one {@code load_skill} away; without one the block is byte-identical
     * to what it always was.
     *
     * <p>A withheld skill is listed too, with {@code status} and {@code blocked_by} instead of being
     * absent. The body is still not shipped — that is the whole point of the gate — but the agent can
     * now tell the user what it cannot do and what to fix, rather than behaving as if the skill had
     * never been bound. The reason codes are the gate's own
     * ({@link ru.agimate.controlapi.service.AgentSkillService.RequirementState}), not prose: the
     * catalogue stays enumerable.
     */
    private static RunBlock skillsBlock(List<AgentSkillWithConnectorsResponse> skills,
                                        List<WithheldSkill> withheld, boolean onDemand) {
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
            if (onDemand && s.disclosure() == Disclosure.LAZY) {
                lines.add("  disclosure: lazy");
            }
        }
        for (WithheldSkill s : withheld) {
            lines.add("- skill_id: " + s.skillId());
            if (s.name() != null && !s.name().isBlank()) {
                lines.add("  name: " + s.name());
            }
            if (s.description() != null && !s.description().isBlank()) {
                lines.add("  description: " + s.description());
            }
            if (!s.connectorCodes().isEmpty()) {
                lines.add("  connector_codes: " + String.join(", ", s.connectorCodes()));
            }
            lines.add("  status: unavailable");
            lines.add("  blocked_by: " + s.blockers().stream()
                    .map(b -> b.key() + " (" + b.code() + ") — " + b.state())
                    .collect(Collectors.joining("; ")));
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
     * <p>{@code sessionId} is the conversation's session — the prompt channel's, otherwise the answer
     * channel's: a block may depend on it (ACP reports the root of the open IDE project, the subagents
     * connector lists the conversation's children).
     */
    private void collectConnectorBlocks(List<Connection> connections, Agent agent, UUID promptChannelId,
                                        UUID sessionId,
                                        List<RunBlock> systemBlocks, List<RunBlock> userBlocks) {
        for (Connection connection : connections) {
            PromptBlockProvider provider = connectorRegistry
                    .findCapability(connection.getConnectorCode(), PromptBlockProvider.class)
                    .orElse(null);
            if (provider == null) {
                continue;
            }
            ConnectorEnv env = envFactory.internal(connection.getId().toString(), agent.getUserId(),
                    agent.getId(), null, promptChannelId, sessionId);
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

    /**
     * The dialogue's text: extracted by the same {@code ChannelHandler.handleInput} as at dispatch
     * ({@link InboundTextResolver}). It falls back to the untrusted event block when the channel or
     * handler is gone or no text could be extracted.
     */
    private RunBlock dialoguePromptBlock(Optional<InboundMessage> inbound, Trigger trigger) {
        return inbound.map(InboundMessage::text)
                .filter(text -> text != null && !text.isBlank())
                .map(text -> RunBlock.trusted("", "user", text, Map.of()))
                .orElseGet(() -> {
                    log.warn("Prompt channel unusable for trigger {} — falling back to event block",
                            trigger.id());
                    return TriggerBlocks.eventBlock(trigger);
                });
    }

    /** Inbound attachments → {@link InboundPart} references (only image/video/audio/file reach the context). */
    private static List<InboundPart> inboundParts(Optional<InboundMessage> inbound) {
        return inbound.map(m -> m.parts().stream()
                        .map(p -> new InboundPart(p.storageRef(), p.version(), p.type(), p.mime(), p.size(), partName(p)))
                        .toList())
                .orElse(List.of());
    }

    private static String partName(Part part) {
        Object name = part.meta() != null ? part.meta().get("name") : null;
        return name != null ? name.toString() : "";
    }
}
