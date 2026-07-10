package ru.agimate.controlapi.service.runcontext;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
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
import ru.agimate.controlapi.connectors.core.dto.ConnectorToolSpec;
import ru.agimate.controlapi.connectors.core.dto.PromptBlock;
import ru.agimate.controlapi.connectors.integrations.mcp.McpToolMapper;
import ru.agimate.controlapi.controller.agent.dto.AgentSkillWithConnectorsResponse;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.entities.AgentSkill;
import ru.agimate.controlapi.database.entities.AgenticTeam;
import ru.agimate.controlapi.database.entities.ChannelSessionMessage;
import ru.agimate.controlapi.database.entities.Connection;
import ru.agimate.controlapi.database.entities.Connector;
import ru.agimate.controlapi.database.entities.Skill;
import ru.agimate.controlapi.database.entities.TriggerLog;
import ru.agimate.controlapi.database.entities.TriggerLogAgent;
import ru.agimate.controlapi.database.enums.ChannelSessionMessageKind;
import ru.agimate.controlapi.database.enums.IdentityScope;
import ru.agimate.controlapi.database.repositories.AgentRepository;
import ru.agimate.controlapi.database.repositories.AgentSkillRepository;
import ru.agimate.controlapi.database.repositories.AgenticTeamRepository;
import ru.agimate.controlapi.database.repositories.ChannelSessionMessageRepository;
import ru.agimate.controlapi.database.repositories.ConnectionRepository;
import ru.agimate.controlapi.database.repositories.ConnectionToolRepository;
import ru.agimate.controlapi.database.repositories.ConnectorRepository;
import ru.agimate.controlapi.database.repositories.SkillRepository;
import ru.agimate.controlapi.database.repositories.TriggerLogAgentRepository;
import ru.agimate.controlapi.service.AgentSkillService;
import ru.agimate.controlapi.service.channel.InboundTextResolver;
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
 * Сборка контекста рана для {@code GetRunContext}: политика ({@link ContextSpec}) выбирается по
 * снапшоту каналов маршрута ({@code trigger_log_agents.channels}), блоки собираются из
 * agent-спеки, {@link PromptBlockProvider}-коннекторов, команды и скиллов; тулы скоупятся
 * скиллами. Воркер получает готовые упорядоченные блоки и только рендерит их.
 *
 * <p>Порядок system-блоков — контракт (стабильные первыми, дружелюбно к prompt-cache):
 * agent → инструкции агента → блоки коннекторов → team → skills → тела подошедших скиллов →
 * trigger guidance. Основной промпт рана — последний user-блок.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RunContextService {

    /** Trigger-path guidance (trusted instructions): автономная обработка событий, не диалог. */
    static final String TRIGGER_GUIDANCE =
            "- Это автономная обработка внешних событий, а не диалог. Если по "
            + "событиям ничего делать не требуется — отвечать не обязательно; можно "
            + "ответить очень кратко, например: «Решено проигнорировать, действия не "
            + "требуются».\n"
            + "- Каждый вызов инструмента должен быть обоснован: вызывай инструмент "
            + "только когда событие действительно требует действия, и коротко поясняй "
            + "причину вызова.";

    /** Детерминированная сериализация события (sorted keys) — одинаковый блок при любом порядке мапы. */
    private static final ObjectMapper EVENT_MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private final TriggerLogAgentRepository triggerLogAgentRepository;
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
    private final InboundTextResolver inboundTextResolver;
    private final ChannelSessionMessageRepository messageRepository;

    public RunContextView build(UUID agentId, UUID triggerId) {
        TriggerLogAgent run = triggerLogAgentRepository.findById(triggerId)
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
        Trigger trigger = reconstructTrigger(run.getTriggerLog());

        // Скиллы: listed — всегда; scoped определяет тела (SYSTEM_TRIGGER) и скоуп тулов.
        List<AgentSkillWithConnectorsResponse> listed = listedSkills(agentId);
        List<AgentSkillWithConnectorsResponse> scoped = spec.loadsSkillBodies()
                ? matchedSkills(listed, trigger)
                : listed;
        Set<String> requiredConnectors = new LinkedHashSet<>();
        scoped.forEach(s -> requiredConnectors.addAll(s.connectorCodes()));

        List<Connection> connections = connectionRepository.findActiveBoundToAgent(agentId);
        UUID promptChannelId = channels != null && channels.prompt() != null
                ? channels.prompt().channelId() : null;

        List<RunBlock> systemBlocks = new ArrayList<>();
        List<RunBlock> userBlocks = new ArrayList<>();

        systemBlocks.add(agentBlock(agent));
        if (agent.getInstructions() != null && !agent.getInstructions().isBlank()) {
            systemBlocks.add(RunBlock.trusted("", "agent", agent.getInstructions().strip(), Map.of()));
        }
        collectConnectorBlocks(connections, agent, promptChannelId, systemBlocks, userBlocks);
        teamBlock(agent).ifPresent(systemBlocks::add);
        if (!listed.isEmpty()) {
            systemBlocks.add(skillsBlock(listed));
        }
        if (spec.loadsSkillBodies()) {
            systemBlocks.addAll(skillBodyBlocks(scoped));
        }
        if (spec.appendsTriggerGuidance()) {
            systemBlocks.add(RunBlock.trusted("trigger_guidance", "guidance", TRIGGER_GUIDANCE, Map.of()));
        }

        // Основной промпт рана — последним user-блоком.
        userBlocks.add(spec == ContextSpec.DIALOGUE
                ? dialoguePromptBlock(channels, trigger)
                : eventBlock(trigger));

        List<RunTool> tools = collectTools(connections, requiredConnectors);
        List<RunHistoryMessage> history = history(run.getSessionId(), spec.historyDetail());
        log.debug("run context agent={} trigger={} spec={} blocks={}/{} tools={} history={}",
                agentId, triggerId, spec, systemBlocks.size(), userBlocks.size(), tools.size(), history.size());
        return new RunContextView(List.copyOf(systemBlocks), List.copyOf(userBlocks), tools, history);
    }

    // ===== История =====

    private static final int HISTORY_WINDOW = 50;

    /**
     * История сессии «как видел пользователь»: только завершённые раны ({@code completed=true} —
     * поэтому сообщения текущего рана, включая его inbound-ack, сюда не попадают), хвост окном
     * {@value #HISTORY_WINDOW}, фильтр по {@link ContextSpec.HistoryDetail}. Дореформенные строки
     * маппятся на v2-виды (REQUEST → INBOUND, RESPONSE → ANSWER) по текстовой проекции.
     */
    private List<RunHistoryMessage> history(UUID sessionId, ContextSpec.HistoryDetail detail) {
        if (sessionId == null) {
            return List.of();
        }
        List<ChannelSessionMessage> tail = messageRepository
                .findBySessionIdAndCompletedTrueOrderByIdDesc(sessionId, PageRequest.of(0, HISTORY_WINDOW));
        List<RunHistoryMessage> history = new ArrayList<>(tail.size());
        for (int i = tail.size() - 1; i >= 0; i--) {
            ChannelSessionMessage m = tail.get(i);
            if (m.getMessage() == null || m.getMessage().isBlank()) {
                continue;
            }
            ChannelSessionMessageKind kind = switch (m.getKind()) {
                case REQUEST -> ChannelSessionMessageKind.INBOUND;
                case RESPONSE -> ChannelSessionMessageKind.ANSWER;
                default -> m.getKind();
            };
            if (kind == ChannelSessionMessageKind.PROGRESS && excludedProgress(m, detail)) {
                continue;
            }
            history.add(new RunHistoryMessage(kind, m.getMessage()));
        }
        return history;
    }

    private static boolean excludedProgress(ChannelSessionMessage m, ContextSpec.HistoryDetail detail) {
        return switch (detail) {
            case FULL -> false;
            case NO_REASONING -> "THINKING".equals(m.getProgressType());
            case DIALOGUE_ONLY -> true;
        };
    }

    // ===== Скиллы =====

    private List<AgentSkillWithConnectorsResponse> listedSkills(UUID agentId) {
        List<UUID> skillIds = agentSkillRepository.findByAgentId(agentId).stream()
                .map(AgentSkill::getSkillId)
                .toList();
        Map<UUID, AgentSkillWithConnectorsResponse> resolved = agentSkillService.resolveSkillsById(skillIds);
        return skillIds.stream().map(resolved::get).filter(Objects::nonNull).toList();
    }

    /** Скилл подходит триггеру, если его connector_codes содержат connectorCode события. */
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

    // ===== Блоки коннекторов =====

    /**
     * Блоки {@link PromptBlockProvider}-коннекторов по активным привязанным connections.
     * Сбой одного провайдера не роняет контекст — ран уходит без его блоков (warn в лог).
     * Ephemeral для user-блоков выводится из {@code stable}: волатильный user-блок
     * (memory notes) меняется каждый ран и в историю не персистится.
     */
    private void collectConnectorBlocks(List<Connection> connections, Agent agent, UUID promptChannelId,
                                        List<RunBlock> systemBlocks, List<RunBlock> userBlocks) {
        for (Connection connection : connections) {
            PromptBlockProvider provider = connectorRegistry
                    .findCapability(connection.getConnectorCode(), PromptBlockProvider.class)
                    .orElse(null);
            if (provider == null) {
                continue;
            }
            ConnectorEnv env = envFactory.internal(
                    connection.getId().toString(), agent.getUserId(), agent.getId(), promptChannelId);
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

    // ===== Основной промпт =====

    /**
     * Текст диалога: извлекается тем же {@code ChannelHandler.handleInput}, что и при dispatch
     * ({@link InboundTextResolver}). Fallback на untrusted-блок события, если канал/handler
     * исчезли или текст не извлёкся.
     */
    private RunBlock dialoguePromptBlock(Channels channels, Trigger trigger) {
        return inboundTextResolver.resolve(channels.prompt().channelId(), trigger)
                .map(text -> RunBlock.trusted("", "user", text, Map.of()))
                .orElseGet(() -> {
                    log.warn("Prompt channel {} unusable for trigger {} — falling back to event block",
                            channels.prompt().channelId(), trigger.id());
                    return eventBlock(trigger);
                });
    }

    /** Событие как данные: untrusted-блок, обёртку/преамбулу ставит рендерер воркера. */
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

    private static Trigger reconstructTrigger(TriggerLog log) {
        return new Trigger(
                log.getConnectorCode(),
                log.getConnectionId(),
                log.getName(),
                log.getExternalId(),
                log.getInput(),
                log.getOccurredAt() == null ? null : log.getOccurredAt().toString(),
                null);
    }

    // ===== Тулы =====

    /** Тулы connections, чей коннектор требуется скоупленными скиллами (порт логики воркера + GetConnectionTools). */
    private List<RunTool> collectTools(List<Connection> connections, Set<String> requiredConnectors) {
        List<RunTool> tools = new ArrayList<>();
        for (Connection connection : connections) {
            if (!requiredConnectors.contains(connection.getConnectorCode())) {
                continue;
            }
            Connector connector = connectorRepository.findById(connection.getConnectorCode()).orElse(null);
            if (connector == null || connector.getToolBinding() == null) {
                continue;
            }
            Map<String, ConnectorToolSpec> specs = switch (connector.getToolBinding()) {
                case STATIC -> connectorRegistry
                        .findCapability(connection.getConnectorCode(), ToolProvider.class)
                        .map(p -> p.getTools(ConnectorEnvFactory.listing(connection.getId())))
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
                .forEach(tool -> tools.put(tool.getName(), McpToolMapper.toSpec(tool)));
        return tools;
    }

    /**
     * Неймспейс экземпляра для LLM-имени тула ({@code {namespace}.{name}}): INSTANCE-коннекторы →
     * {@code full_code}; контекстные синглтоны → {@code connector_code}.
     */
    private static String namespaceOf(Connection connection) {
        String ns = connection.getIdentityScope() == IdentityScope.INSTANCE
                ? connection.getFullCode()
                : connection.getConnectorCode();
        return ns == null ? "" : ns;
    }
}
