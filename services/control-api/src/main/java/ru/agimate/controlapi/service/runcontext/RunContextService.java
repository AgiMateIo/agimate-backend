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
import ru.agimate.common.util.JsonUtils;
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
import ru.agimate.controlapi.database.entities.ChannelSessionMessage;
import ru.agimate.controlapi.database.entities.Connection;
import ru.agimate.controlapi.database.entities.Connector;
import ru.agimate.controlapi.database.entities.Skill;
import ru.agimate.controlapi.database.entities.AgentRun;
import ru.agimate.controlapi.database.enums.ChannelSessionMessageKind;
import ru.agimate.controlapi.connectors.core.InternalConnectorHandler;
import ru.agimate.controlapi.database.repositories.AgentRepository;
import ru.agimate.controlapi.database.repositories.AgentSkillRepository;
import ru.agimate.controlapi.database.repositories.AgenticTeamRepository;
import ru.agimate.controlapi.database.repositories.ChannelRepository;
import ru.agimate.controlapi.database.repositories.ChannelSessionMessageRepository;
import ru.agimate.controlapi.database.repositories.ConnectionRepository;
import ru.agimate.controlapi.database.repositories.ConnectionToolRepository;
import ru.agimate.controlapi.database.repositories.ConnectorRepository;
import ru.agimate.controlapi.database.repositories.SkillRepository;
import ru.agimate.controlapi.database.repositories.AgentRunRepository;
import ru.agimate.controlapi.service.AgentSkillService;
import ru.agimate.controlapi.service.channel.InboundTextResolver;
import ru.agimate.controlapi.service.dto.ToolTurnRecord;
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
import java.util.stream.Collectors;

/**
 * Сборка контекста рана для {@code GetRunContext}: политика ({@link ContextSpec}) выбирается по
 * снапшоту каналов маршрута ({@code agent_runs.channels}), блоки собираются из
 * agent-спеки, {@link PromptBlockProvider}-коннекторов, команды и скиллов; тулы скоупятся
 * скиллами. Воркер получает готовые упорядоченные блоки и только рендерит их.
 *
 * <p>Порядок system-блоков — контракт (стабильные первыми, дружелюбно к prompt-cache):
 * agent → инструкции агента → блоки коннекторов → team → skills → тела скиллов (в диалоге — все,
 * в trigger-ране — подошедшие коннектору события) →
 * trigger guidance. Основной промпт рана — последний user-блок.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RunContextService {

    // Константы ниже — русский первоисточник и фолбэк: на инсталляции с другим языком блок берётся
    // из seed/<lang>/prompt.properties по ключам PromptTexts.RUN_*.

    /** Trigger-path guidance (trusted instructions): автономная обработка событий, не диалог. */
    static final String TRIGGER_GUIDANCE =
            "- Это автономная обработка внешних событий, а не диалог. Если по "
            + "событиям ничего делать не требуется — отвечать не обязательно; можно "
            + "ответить очень кратко, например: «Решено проигнорировать, действия не "
            + "требуются».\n"
            + "- Каждый вызов инструмента должен быть обоснован: вызывай инструмент "
            + "только когда событие действительно требует действия, и коротко поясняй "
            + "причину вызова.\n"
            + "- Результат работы — только проверяемый артефакт: id файла или задачи из "
            + "результата инструмента, реально выполненный вызов. Если нужного инструмента "
            + "нет или вызов завершился ошибкой — зафиксируй блокер и остановись. Никогда "
            + "не сообщай о выполнении, которого не было, и не выдумывай id файлов.";

    /**
     * Правило вызова инструментов — добавляется при наличии тулов в ране. Слабые модели имитируют
     * вызов текстом («🔧 name»), скопировав паттерн из истории, — такой «вызов» не исполняется.
     */
    static final String TOOL_CALL_GUIDANCE =
            "Инструменты вызывай только через структурный tool-calling API. Никогда не пиши вызов "
            + "инструмента текстом ответа: строки вида «🔧 имя» или «[вызван инструмент …]» — "
            + "служебная разметка уже выполненной работы, а не образец ответа; написанный текстом "
            + "«вызов» не исполняется.";

    /**
     * Attach-конвенция ответа — добавляется только в DIALOGUE-ранах, чей prompt-канал умеет
     * вложения ({@code ChannelHandler.supportsOutboundAttachments()}); иначе агент приложил бы
     * файл, который канал молча не доставит.
     */
    static final String ATTACHMENT_GUIDANCE =
            "Чтобы приложить файл к своему ответу пользователю, вставь в текст ответа маркер "
            + "[[attach:agf_...]] с id файла (формат agf_<uuid>). id можно взять из результата "
            + "инструмента (поле file.id) или из описания загруженного пользователем файла "
            + "(строка «Описание загруженного файла … id: agf_…»). Маркер будет вырезан из текста, "
            + "а файл доставлен в канал вложением (изображение/видео/документ — по типу файла). "
            + "Не выдумывай id: используй только полученные в этом разговоре.";

    /** Детерминированная сериализация события (sorted keys) — одинаковый блок при любом порядке мапы. */
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
    private final ChannelSessionMessageRepository messageRepository;
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
        // Директивы — только из статической декларации кода коннектора (registry); динамические
        // триггеры (connection_triggers) и payload сюда не попадают — незнакомое имя = базовый пресет.
        EffectiveContext effective = EffectiveContext.of(spec, declaredDirectives(trigger));

        // Скиллы: тулы — от ВСЕХ скиллов агента: содержание делегированной через триггер задачи
        // не связано с коннектором события (задача с доски может требовать media). Тела: в диалоге —
        // все (скиллы задают поведение и там), в trigger-ране скоупятся по коннектору события.
        List<AgentSkillWithConnectorsResponse> listed = listedSkills(agentId);
        List<AgentSkillWithConnectorsResponse> scoped = switch (spec.skillBodies()) {
            case ALL -> listed;
            case MATCHED -> matchedSkills(listed, trigger);
        };
        Set<String> requiredConnectors = new LinkedHashSet<>();
        if (effective.skillTools()) {
            listed.forEach(s -> requiredConnectors.addAll(s.connectorCodes()));
        }

        List<Connection> connections = connectionRepository.findActiveBoundToAgent(agentId);
        UUID promptChannelId = channels != null && channels.prompt() != null
                ? channels.prompt().channelId() : null;
        UUID promptSessionId = channels != null && channels.prompt() != null
                ? channels.prompt().sessionId() : null;
        // Канал, приносящий свои тулы (IDE-коннектор), подмешивает коннектор prompt-канала мимо
        // скилл-гейта — «канал приносит тулы», пока разговор идёт из этого канала. Возвращает
        // connection этого канала, чтобы его тулы листались session-aware (session-scoped MCP из IDE).
        UUID sessionAwareConnectionId = addPromptChannelTools(promptChannelId, requiredConnectors);
        // ownConnectionTools: connection события (именно она, не все connections её кода — INSTANCE)
        // попадает в выборку мимо скилл-гейта.
        UUID ownConnectionId = effective.ownConnectionTools()
                ? tryParseUuid(trigger.connectionId()) : null;

        List<RunTool> tools = collectTools(connections, requiredConnectors, ownConnectionId,
                sessionAwareConnectionId, promptSessionId);

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

        // Основной промпт рана — последним user-блоком; вложения inbound — отдельно (мультимодальность),
        // резолвим сообщение один раз: text → блок, parts → RunContextView.
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

        List<RunHistoryMessage> history = history(run.getSessionId(), effective);
        log.debug("run context agent={} trigger={} spec={} blocks={}/{} tools={} history={} parts={}",
                agentId, triggerId, spec, systemBlocks.size(), userBlocks.size(), tools.size(),
                history.size(), inboundParts.size());
        return new RunContextView(List.copyOf(systemBlocks), List.copyOf(userBlocks), tools, history,
                inboundParts);
    }

    // ===== История =====

    /** Кап на один JSON tool-хода (аргументы/результат) в контексте — бюджет важнее полноты. */
    static final int TOOL_JSON_CONTEXT_CAP = 4 * 1024;

    private static final String PROGRESS_TOOL_CALL = "TOOL_CALL";
    private static final String PROGRESS_TOOL_RESULT = "TOOL_RESULT";
    private static final String PROGRESS_TEXT = "TEXT";

    /**
     * История сессии «как видел пользователь»: только завершённые раны ({@code completed=true} —
     * поэтому сообщения текущего рана, включая его inbound-ack, сюда не попадают), хвост окном
     * {@link EffectiveContext#historyLimit()} ({@code 0} — истории нет), фильтр по
     * {@link ContextSpec.HistoryDetail}.
     *
     * <p>Tool-ходы (v2.1): у PROGRESS/TOOL_CALL с {@code message_json} наружу идёт структурный
     * {@code toolTurn} — воркер восстановит нативные tool_use/tool_result; текстовая 🔧-проекция
     * в историю не попадает (модель имитирует её текстом вместо реального вызова). PROGRESS/TEXT
     * такого рана скипается — преамбула уже внутри toolTurn. Легаси 🔧-строки без message_json
     * санитизируются в констатацию «[вызван инструмент …]».
     */
    private List<RunHistoryMessage> history(UUID sessionId, EffectiveContext effective) {
        if (sessionId == null || effective.historyLimit() <= 0) {
            return List.of();
        }
        ContextSpec.HistoryDetail detail = effective.historyDetail();
        List<ChannelSessionMessage> tail = messageRepository
                .findBySessionIdAndCompletedTrueOrderByIdDesc(
                        sessionId, PageRequest.of(0, effective.historyLimit()));
        Set<UUID> structuredRuns = structuredToolRuns(tail);
        List<RunHistoryMessage> history = new ArrayList<>(tail.size());
        for (int i = tail.size() - 1; i >= 0; i--) {
            ChannelSessionMessage m = tail.get(i);
            // TOOL_RESULT-строка (v2.1a) несёт results в message_json при пустом тексте — не скипать.
            if ((m.getMessage() == null || m.getMessage().isBlank()) && !hasStructuredResults(m)) {
                continue;
            }
            ChannelSessionMessageKind kind = m.getKind();
            if (kind == ChannelSessionMessageKind.PROGRESS && excludedProgress(m, detail)) {
                continue;
            }
            RunHistoryMessage mapped = toHistoryMessage(m, kind, structuredRuns);
            if (mapped != null) {
                history.add(mapped);
            }
        }
        return history;
    }

    /** Раны окна, у которых tool-ходы записаны структурно, — их PROGRESS/TEXT дублируют toolTurn.text. */
    private static Set<UUID> structuredToolRuns(List<ChannelSessionMessage> tail) {
        Set<UUID> runs = new LinkedHashSet<>();
        for (ChannelSessionMessage m : tail) {
            if (m.getKind() == ChannelSessionMessageKind.PROGRESS
                    && PROGRESS_TOOL_CALL.equals(m.getProgressType())
                    && m.getMessageJson() != null) {
                runs.add(m.getRunId());
            }
        }
        return runs;
    }

    private static RunHistoryMessage toHistoryMessage(ChannelSessionMessage m, ChannelSessionMessageKind kind,
                                                      Set<UUID> structuredRuns) {
        if (kind != ChannelSessionMessageKind.PROGRESS) {
            return new RunHistoryMessage(kind, m.getMessage());
        }
        if (PROGRESS_TOOL_CALL.equals(m.getProgressType())) {
            if (m.getMessageJson() != null) {
                Optional<ToolTurnRecord> turn = JsonUtils.fromMap(m.getMessageJson(), ToolTurnRecord.class);
                if (turn.isPresent()) {
                    return new RunHistoryMessage(kind, m.getMessage(), capToolTurn(turn.get()));
                }
                log.warn("unreadable tool turn message_json run={} seq={} — falling back to text",
                        m.getRunId(), m.getSeq());
            }
            // Легаси-строка «🔧 name»: имитируемое действие → констатация прошлой работы.
            return new RunHistoryMessage(kind, sanitizeToolLines(m.getMessage()));
        }
        if (PROGRESS_TOOL_RESULT.equals(m.getProgressType())) {
            // results-половина хода (v2.1a): отдаём воркеру structured, он сошьёт с предыдущей calls-строкой.
            Optional<ToolTurnRecord> turn = JsonUtils.fromMap(m.getMessageJson(), ToolTurnRecord.class);
            if (turn.isPresent()) {
                return new RunHistoryMessage(kind, "", capToolTurn(turn.get()));
            }
            log.warn("unreadable tool result message_json run={} seq={} — dropping", m.getRunId(), m.getSeq());
            return null;
        }
        if (PROGRESS_TEXT.equals(m.getProgressType()) && structuredRuns.contains(m.getRunId())) {
            return null; // преамбула уже в toolTurn.text
        }
        return new RunHistoryMessage(kind, m.getMessage());
    }

    /** TOOL_RESULT-строка (v2.1a) с сохранённым message_json — results-половина хода, текст пуст. */
    private static boolean hasStructuredResults(ChannelSessionMessage m) {
        return m.getKind() == ChannelSessionMessageKind.PROGRESS
                && PROGRESS_TOOL_RESULT.equals(m.getProgressType())
                && m.getMessageJson() != null;
    }

    private static boolean excludedProgress(ChannelSessionMessage m, ContextSpec.HistoryDetail detail) {
        return switch (detail) {
            case FULL -> false;
            case NO_REASONING -> "THINKING".equals(m.getProgressType());
            case DIALOGUE_ONLY -> true;
        };
    }

    /** «🔧 name» → «[вызван инструмент name]»: прошедшее время нельзя «исполнить», имитация теряет смысл. */
    static String sanitizeToolLines(String text) {
        return text.lines()
                .map(line -> line.startsWith("🔧 ")
                        ? "[вызван инструмент " + line.substring("🔧 ".length()).strip() + "]"
                        : line)
                .collect(Collectors.joining("\n"));
    }

    /** Обрезка JSON-полей tool-хода до контекстного бюджета {@value #TOOL_JSON_CONTEXT_CAP}. */
    private static ToolTurnRecord capToolTurn(ToolTurnRecord turn) {
        return new ToolTurnRecord(
                turn.text(),
                turn.calls().stream()
                        .map(c -> new ToolTurnRecord.Call(c.id(), c.name(), capJson(c.argumentsJson())))
                        .toList(),
                turn.results().stream()
                        .map(r -> new ToolTurnRecord.Result(r.id(), r.name(), capJson(r.outputJson()),
                                r.failed()))
                        .toList());
    }

    private static String capJson(String json) {
        if (json == null || json.length() <= TOOL_JSON_CONTEXT_CAP) {
            return json;
        }
        return json.substring(0, TOOL_JSON_CONTEXT_CAP) + "…[truncated]";
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
                    connection.getId().toString(), agent.getUserId(), agent.getId(), null, promptChannelId, null);
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
    /** Умеет ли handler prompt-канала доставлять вложения из ответа ({@code [[attach:…]]}). */
    private boolean promptChannelSupportsAttachments(Channels channels) {
        if (channels == null || channels.prompt() == null) {
            return false;
        }
        return channelRepository.findByIdAndDeletedAtIsNull(channels.prompt().channelId())
                .flatMap(channel -> channelHandlerRegistry.find(channel.getChannelHandler()))
                .map(ChannelHandler::supportsOutboundAttachments)
                .orElse(false);
    }

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

    /** Вложения inbound → ссылки {@link InboundPart} (только image/video/audio/file идут в контекст). */
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

    /**
     * Основной блок события по {@link EffectiveContext#presentation()}: {@code PROMPT} — trusted-текст
     * из {@code data[promptParam]} (декларация только internal-коннекторов, guard на бутстрапе;
     * авторство текста — агент/платформа), пусто/не строка → фолбэк на untrusted событие.
     */
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

    /** Статические директивы триггера из registry ({@code null} — не объявлены/динамический триггер). */
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


    // ===== Тулы =====

    /**
     * Если prompt-канал приносит свои тулы ({@link ChannelHandler#contributesPromptTools}), его
     * коннектор добавляется в {@code requiredConnectors} — {@link #collectTools} подхватит тулы
     * соответствующего binding'а независимо от скиллов агента.
     *
     * @return connection этого канала (session-aware листинг), либо {@code null}
     */
    private UUID addPromptChannelTools(UUID promptChannelId, Set<String> requiredConnectors) {
        if (promptChannelId == null) {
            return null;
        }
        return channelRepository.findByIdAndDeletedAtIsNull(promptChannelId)
                .filter(channel -> channelHandlerRegistry.find(channel.getChannelHandler())
                        .filter(ChannelHandler::contributesPromptTools).isPresent())
                .map(channel -> {
                    requiredConnectors.add(channel.getConnectorCode());
                    return channel.getConnectionId();
                })
                .orElse(null);
    }

    /**
     * Тулы connections, чей коннектор требуется скоупленными скиллами, плюс {@code ownConnectionId}
     * (connection события при {@code ownConnectionTools} — адресно, мимо скилл-гейта). Для
     * {@code sessionAwareConnectionId} (connection prompt-канала, приносящего тулы) STATIC-листинг
     * получает env с {@code promptSessionId}, чтобы коннектор мог отдать session-scoped тулы (MCP из IDE).
     */
    private List<RunTool> collectTools(List<Connection> connections, Set<String> requiredConnectors,
                                       UUID ownConnectionId, UUID sessionAwareConnectionId,
                                       UUID promptSessionId) {
        List<RunTool> tools = new ArrayList<>();
        for (Connection connection : connections) {
            if (!requiredConnectors.contains(connection.getConnectorCode())
                    && !connection.getId().equals(ownConnectionId)) {
                continue;
            }
            Connector connector = connectorRepository.findById(connection.getConnectorCode()).orElse(null);
            if (connector == null || connector.getDefinitionBinding() == null) {
                continue;
            }
            ConnectorEnv listingEnv = connection.getId().equals(sessionAwareConnectionId)
                    ? envFactory.internal(connection.getId().toString(), null, null, null, null, promptSessionId)
                    : ConnectorEnvFactory.listing(connection.getId());
            Map<String, ConnectorToolSpec> specs = switch (connector.getDefinitionBinding()) {
                case STATIC -> connectorRegistry
                        .findCapability(connection.getConnectorCode(), ToolProvider.class)
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
     * Неймспейс экземпляра для LLM-имени тула ({@code {namespace}.{name}}): внешние экземпляры →
     * {@code full_code}; внутренние строки-режимы → {@code connector_code}. «Внутренний/внешний» —
     * знание реестра (тип хендлера), не поля connection.
     */
    private String namespaceOf(Connection connection) {
        boolean internal = connectorRegistry.findHandler(connection.getConnectorCode())
                .map(InternalConnectorHandler.class::isInstance)
                .orElse(false);
        String ns = internal ? connection.getConnectorCode() : connection.getFullCode();
        return ns == null ? "" : ns;
    }
}
