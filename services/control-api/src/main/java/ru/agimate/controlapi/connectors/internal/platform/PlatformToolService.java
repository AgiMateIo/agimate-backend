package ru.agimate.controlapi.connectors.internal.platform;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;
import ru.agimate.common.rest.error.BaseHttpStatusException;
import ru.agimate.controlapi.connectors.core.ConnectorEnvHolder;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.connectors.core.ConnectorRegistry;
import ru.agimate.controlapi.connectors.core.TriggerProvider;
import ru.agimate.controlapi.connectors.core.annotation.Tool;
import ru.agimate.controlapi.connectors.core.annotation.ToolAnnotations;
import ru.agimate.controlapi.connectors.core.annotation.ToolParam;
import ru.agimate.controlapi.connectors.internal.platform.dto.PlatformDtos.AgentBrief;
import ru.agimate.controlapi.connectors.internal.platform.dto.PlatformDtos.AgentDetail;
import ru.agimate.controlapi.connectors.internal.platform.dto.PlatformDtos.AgentList;
import ru.agimate.controlapi.connectors.internal.platform.dto.PlatformDtos.BoundSkill;
import ru.agimate.controlapi.connectors.internal.platform.dto.PlatformDtos.ConnectionBrief;
import ru.agimate.controlapi.connectors.internal.platform.dto.PlatformDtos.ConnectionList;
import ru.agimate.controlapi.connectors.internal.platform.dto.PlatformDtos.ConnectionSetup;
import ru.agimate.controlapi.connectors.internal.platform.dto.PlatformDtos.ConnectorBrief;
import ru.agimate.controlapi.connectors.internal.platform.dto.PlatformDtos.ConnectorDetail;
import ru.agimate.controlapi.connectors.internal.platform.dto.PlatformDtos.ConnectorList;
import ru.agimate.controlapi.connectors.internal.platform.dto.PlatformDtos.CreatedAgent;
import ru.agimate.controlapi.connectors.internal.platform.dto.PlatformDtos.FileBrief;
import ru.agimate.controlapi.connectors.internal.platform.dto.PlatformDtos.FileList;
import ru.agimate.controlapi.connectors.internal.platform.dto.PlatformDtos.OperationResult;
import ru.agimate.controlapi.connectors.internal.platform.dto.PlatformDtos.SkillBrief;
import ru.agimate.controlapi.connectors.internal.platform.dto.PlatformDtos.SkillDetail;
import ru.agimate.controlapi.connectors.internal.platform.dto.PlatformDtos.SkillList;
import ru.agimate.controlapi.connectors.internal.platform.dto.PlatformDtos.ToolBrief;
import ru.agimate.controlapi.connectors.internal.platform.dto.PlatformDtos.TriggerBrief;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.entities.AgentSkill;
import ru.agimate.controlapi.database.entities.Connection;
import ru.agimate.controlapi.database.entities.Connector;
import ru.agimate.controlapi.database.entities.Skill;
import ru.agimate.controlapi.database.entities.StoredFile;
import ru.agimate.controlapi.database.enums.AgentType;
import ru.agimate.controlapi.database.repositories.AgentRepository;
import ru.agimate.controlapi.database.repositories.AgentSkillRepository;
import ru.agimate.controlapi.database.repositories.ConnectionRepository;
import ru.agimate.controlapi.database.repositories.ConnectorRepository;
import ru.agimate.controlapi.database.repositories.SkillRepository;
import ru.agimate.controlapi.database.repositories.StoredFileRepository;
import ru.agimate.controlapi.database.repositories.SkillSpecs;
import ru.agimate.controlapi.service.AgentService;
import ru.agimate.controlapi.service.AgentSkillService;
import ru.agimate.controlapi.service.SkillService;
import ru.agimate.controlapi.service.SystemSkillBootstrap;
import ru.agimate.controlapi.service.connection.ConnectionBindingService;
import ru.agimate.controlapi.service.dto.agent.AgentCreateCommand;
import ru.agimate.controlapi.service.dto.agent.AgentUpdateCommand;
import ru.agimate.controlapi.service.tool.ToolDefinitionService;
import ru.agimate.controlapi.storage.FileIds;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Tools of the platform connector — the meta-agent manages the platform (agents, skills,
 * connections) on behalf of its human owner ({@code env.userId}). A thin adapter: reads come from the
 * repositories, writes go through the existing services (command overloads, so as not to drag in
 * {@code controller/**}). Domain {@link BaseHttpStatusException}s are translated into
 * {@link ConnectorException} so the message reaches the agent. The guard {@link #requireNotSelf}: an
 * agent does not manage itself.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlatformToolService {

    private static final int MAX_LISTING = 100;

    @Value("${app.frontend.base-url}")
    private String frontendBaseUrl;

    private final ConnectorRegistry connectorRegistry;
    private final ConnectorRepository connectorRepository;
    private final ToolDefinitionService toolDefinitionService;
    private final SkillRepository skillRepository;
    private final SkillService skillService;
    private final AgentRepository agentRepository;
    private final AgentSkillRepository agentSkillRepository;
    private final AgentSkillService agentSkillService;
    private final AgentService agentService;
    private final ConnectionRepository connectionRepository;
    private final ConnectionBindingService connectionBindingService;
    private final StoredFileRepository storedFileRepository;

    // ---- discovery -------------------------------------------------------------------------

    @Tool(name = "list_connectors",
            description = "List available connectors in the platform catalog. 'integration'=true means "
                    + "it needs a connection (credentials); integration=false connectors are attached "
                    + "to an agent via skills, not connections",
            annotations = @ToolAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = false))
    public ConnectorList listConnectors(
            @ToolParam(value = "Optional full-text search over connector name/description", required = false)
            String search) {
        String q = blankToNull(search);
        List<ConnectorBrief> items = connectorRepository.search(q, PageRequest.of(0, MAX_LISTING,
                        Sort.by("name").ascending()))
                .map(c -> new ConnectorBrief(c.getCode(), c.getName(), c.getDescription(), c.isIntegration()))
                .getContent();
        return new ConnectorList(items);
    }

    @Tool(name = "get_connector",
            description = "Get a connector's details including the tools and triggers it provides — use "
                    + "this to understand a connector's capabilities before writing a skill for it",
            annotations = @ToolAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = false))
    public ConnectorDetail getConnector(
            @ToolParam("Connector code (e.g. telegram, board, persist-memory)") String code) {
        Connector connector = connectorRepository.findById(requireText(code, "code"))
                .orElseThrow(() -> new ConnectorException("Connector not found: " + code));

        List<ToolBrief> tools = toolDefinitionService.getCatalogTools(connector.getCode()).values().stream()
                .map(t -> new ToolBrief(t.name(), t.description()))
                .toList();
        List<TriggerBrief> triggers = connectorRegistry.findCapability(connector.getCode(), TriggerProvider.class)
                .map(tp -> tp.getTriggers().entrySet().stream()
                        .map(e -> new TriggerBrief(e.getKey(), e.getValue().description()))
                        .toList())
                .orElseGet(List::of);

        return new ConnectorDetail(connector.getCode(), connector.getName(), connector.getDescription(),
                connector.isIntegration(), tools, triggers);
    }

    @Tool(name = "list_skills",
            description = "List skills. scope=MINE (your own, default) or PUBLIC (shared catalog). "
                    + "A skill is a SKILL.md document; use get_skill for its full body",
            annotations = @ToolAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = false))
    public SkillList listSkills(
            @ToolParam(value = "MINE or PUBLIC (default MINE)", required = false) String scope,
            @ToolParam(value = "Optional search over name/description", required = false) String search,
            @ToolParam(value = "Filter by required connector code", required = false) String connectorCode) {
        Specification<Skill> spec = "PUBLIC".equalsIgnoreCase(blankToNull(scope))
                ? SkillSpecs.isPublic()
                : SkillSpecs.ownedBy(userId());
        spec = spec.and(SkillSpecs.notDeleted());
        String searchQ = blankToNull(search);
        if (searchQ != null) {
            spec = spec.and(SkillSpecs.searchByNameOrDescription(searchQ));
        }
        String connQ = blankToNull(connectorCode);
        if (connQ != null) {
            spec = spec.and(SkillSpecs.hasConnector(connQ));
        }
        List<SkillBrief> items = skillRepository.findAll(spec, PageRequest.of(0, MAX_LISTING,
                        Sort.by("name").ascending()))
                .map(this::toSkillBrief)
                .getContent();
        return new SkillList(items);
    }

    @Tool(name = "get_skill", description = "Get a skill's full SKILL.md body and metadata",
            annotations = @ToolAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = false))
    public SkillDetail getSkill(@ToolParam("Skill public ID") String skillId) {
        Skill skill = accessibleSkill(parseUuid(skillId, "skillId"));
        return new SkillDetail(skill.getId().toString(), skill.getName(), displayTitle(skill),
                skill.getDescription(), skill.getConnectorCodes(), skill.getVersion(),
                Boolean.TRUE.equals(skill.getIsPublic()), isSystem(skill), skill.getMdContent());
    }

    @Tool(name = "list_agents", description = "List the agents you own",
            annotations = @ToolAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = false))
    public AgentList listAgents(
            @ToolParam(value = "Optional search over agent name/description", required = false) String search) {
        String q = blankToNull(search);
        var page = PageRequest.of(0, MAX_LISTING, Sort.by("name").ascending());
        var agents = (q != null
                ? agentRepository.searchForUser(userId(), null, q, page)
                : agentRepository.findByUserId(userId(), page));
        return new AgentList(agents.map(this::toAgentBrief).getContent());
    }

    @Tool(name = "get_agent",
            description = "Get an agent's full config: instructions, type, team and bound skills (with the "
                    + "connectors each skill requires). Cross-reference list_connections to see which "
                    + "required connectors still need a connection",
            annotations = @ToolAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = false))
    public AgentDetail getAgent(@ToolParam("Agent public ID") String agentId) {
        Agent agent = ownedAgent(parseUuid(agentId, "agentId"));
        List<UUID> skillIds = agentSkillRepository.findByAgentId(agent.getId()).stream()
                .map(AgentSkill::getSkillId)
                .toList();
        List<BoundSkill> skills = skillIds.isEmpty() ? List.of()
                : skillRepository.findByIdInNotDeleted(skillIds).stream()
                .map(s -> new BoundSkill(s.getId().toString(), s.getName(), s.getConnectorCodes()))
                .toList();
        return new AgentDetail(agent.getId().toString(), agent.getName(), agent.getDescription(),
                agent.getInstructions(), agent.getType().name(), agent.isEnabled(),
                agent.getAgenticTeamId() == null ? null : agent.getAgenticTeamId().toString(), skills);
    }

    @Tool(name = "list_files",
            description = "Find a file the owner shared with you or you produced earlier — a photo "
                    + "from the conversation, a generated image, an exported table. Use it when the "
                    + "agf_ id is no longer in what you can see: the ids returned here go straight "
                    + "into another tool's file parameter, or into [[attach:agf_…]] to send the file "
                    + "back. Freshest first. Files expire, so an old one may simply be gone.",
            annotations = @ToolAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = false))
    public FileList listFiles(
            @ToolParam(value = "Search the whole account instead of this conversation only "
                    + "(default false)", required = false) Boolean allConversations,
            @ToolParam(value = "Optional substring of the file name", required = false) String name) {
        // Outside a channel flow there is no conversation to narrow to, and refusing would leave the
        // agent with no way to reach its own files at all.
        UUID sessionId = Boolean.TRUE.equals(allConversations)
                ? null : ConnectorEnvHolder.current().sessionId();
        List<StoredFile> files = storedFileRepository.findVisible(userId(), null, sessionId,
                        blankToNull(name), LocalDateTime.now(), PageRequest.of(0, MAX_LISTING))
                .getContent();
        return new FileList(files.stream().map(PlatformToolService::toFileBrief).toList());
    }

    private static FileBrief toFileBrief(StoredFile file) {
        return new FileBrief(FileIds.external(file.getId()), file.getName(), file.getMime(),
                file.getSizeBytes(),
                file.getCreatedAt() != null ? file.getCreatedAt().toString() : null);
    }

    // ---- agents & skills -------------------------------------------------------------------

    @Tool(name = "create_agent",
            description = "Create a new agent. type defaults to GENERIC. Optionally bind skills at "
                    + "creation (their connectors are attached automatically). Returns the agent's ID; "
                    + "its access key is shown once in the platform UI",
            annotations = @ToolAnnotations(destructiveHint = false, openWorldHint = false))
    public CreatedAgent createAgent(
            @ToolParam("Agent name") String name,
            @ToolParam(value = "Agent description", required = false) String description,
            @ToolParam(value = "System instructions / persona for the agent", required = false)
            String instructions,
            @ToolParam(value = "Agent type: GENERIC (default) or CENTRIFUGO", required = false) String type,
            @ToolParam(value = "Skill IDs to bind on creation", required = false) List<String> skillIds) {
        AgentType agentType = parseAgentType(type);
        List<UUID> skills = skillIds == null ? null : skillIds.stream()
                .map(id -> parseUuid(id, "skillIds")).toList();
        var command = new AgentCreateCommand(requireText(name, "name"), blankToNull(description),
                blankToNull(instructions), agentType, null, null, null, skills, null);
        var result = domain(() -> agentService.create(userId(), command));
        return new CreatedAgent(result.agent().getId().toString(), result.agent().getName());
    }

    @Tool(name = "update_agent",
            description = "Update an agent's name, description, instructions and/or enabled flag. "
                    + "Omitted fields are left unchanged",
            annotations = @ToolAnnotations(destructiveHint = true, openWorldHint = false))
    public AgentDetail updateAgent(
            @ToolParam("Agent public ID") String agentId,
            @ToolParam(value = "New name", required = false) String name,
            @ToolParam(value = "New description", required = false) String description,
            @ToolParam(value = "New instructions", required = false) String instructions,
            @ToolParam(value = "Enable or disable the agent", required = false) Boolean enabled) {
        UUID id = parseUuid(agentId, "agentId");
        requireNotSelf(id);
        Agent existing = ownedAgent(id);
        if (existing.getType() == AgentType.WEBHOOK) {
            throw new ConnectorException("WEBHOOK agents are managed in the UI");
        }
        // A blank argument means the agent did not pass it, so it reaches patch as null — "leave alone".
        // The tool therefore cannot erase a field, which is what we want: an erase should be deliberate.
        var command = new AgentUpdateCommand(blankToNull(name), blankToNull(description),
                blankToNull(instructions), null, null, null, enabled);
        domain(() -> agentService.patch(id, userId(), command));
        return getAgent(agentId);
    }

    @Tool(name = "create_skill",
            description = "Create a skill from a full SKILL.md document (YAML frontmatter with name "
                    + "(stable code), title (display name), description, connectors + markdown body). "
                    + "isPublic defaults to false",
            annotations = @ToolAnnotations(destructiveHint = false, openWorldHint = false))
    public SkillDetail createSkill(
            @ToolParam("Full SKILL.md content (frontmatter + body)") String skillMd,
            @ToolParam(value = "Publish to the shared catalog (default false)", required = false)
            Boolean isPublic) {
        Skill created = domain(() -> skillService.create(userId(), requireText(skillMd, "skillMd"),
                Boolean.TRUE.equals(isPublic)));
        return getSkill(created.getId().toString());
    }

    @Tool(name = "update_skill",
            description = "Replace a skill's SKILL.md content (bumps its version). Only your own skills",
            annotations = @ToolAnnotations(destructiveHint = true, openWorldHint = false))
    public SkillDetail updateSkill(
            @ToolParam("Skill public ID") String skillId,
            @ToolParam("Full SKILL.md content (frontmatter + body)") String skillMd,
            @ToolParam(value = "Publish to the shared catalog; omit to keep the current visibility",
                    required = false)
            Boolean isPublic) {
        UUID id = parseUuid(skillId, "skillId");
        domain(() -> skillService.update(id, userId(), false, requireText(skillMd, "skillMd"), isPublic));
        return getSkill(skillId);
    }

    @Tool(name = "bind_skill",
            description = "Bind a skill to an agent. The skill's required connectors and access policies "
                    + "are attached automatically",
            annotations = @ToolAnnotations(destructiveHint = false, openWorldHint = false))
    public OperationResult bindSkill(
            @ToolParam("Agent public ID") String agentId,
            @ToolParam("Skill public ID") String skillId) {
        UUID agent = parseUuid(agentId, "agentId");
        requireNotSelf(agent);
        UUID skill = parseUuid(skillId, "skillId");
        domain(() -> agentSkillService.create(agent, skill, userId()));
        return new OperationResult(true, "Skill bound to agent");
    }

    @Tool(name = "unbind_skill", description = "Unbind a skill from an agent",
            annotations = @ToolAnnotations(destructiveHint = true, openWorldHint = false))
    public OperationResult unbindSkill(
            @ToolParam("Agent public ID") String agentId,
            @ToolParam("Skill public ID") String skillId) {
        UUID agent = parseUuid(agentId, "agentId");
        requireNotSelf(agent);
        UUID skill = parseUuid(skillId, "skillId");
        domain(() -> {
            agentSkillService.delete(agent, skill, userId());
            return null;
        });
        return new OperationResult(true, "Skill unbound from agent");
    }

    // ---- integrations ----------------------------------------------------------------------

    @Tool(name = "list_connections", description = "List your connector connections (integration instances)",
            annotations = @ToolAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = false))
    public ConnectionList listConnections(
            @ToolParam(value = "Filter by connector code", required = false) String connectorCode) {
        List<ConnectionBrief> items = connectionRepository
                .findByUserIdFiltered(userId(), blankToNull(connectorCode), null).stream()
                .map(this::toConnectionBrief)
                .toList();
        return new ConnectionList(items);
    }

    @Tool(name = "create_connection",
            description = "Start connecting an integration. Returns a setup link the user opens to enter "
                    + "credentials — secrets are never handled here. After the user finishes, call "
                    + "list_connections to get the new connection and bind_connection to attach it",
            annotations = @ToolAnnotations(destructiveHint = false, openWorldHint = false))
    public ConnectionSetup createConnection(
            @ToolParam("Integration connector code (e.g. telegram, mcp)") String connectorCode,
            @ToolParam(value = "Optional display name for the connection", required = false) String name) {
        String code = requireText(connectorCode, "connectorCode");
        boolean integration = connectorRepository.findById(code).map(Connector::isIntegration).orElse(false);
        if (!integration) {
            throw new ConnectorException("Not an integration connector: " + code
                    + ". Only integration connectors need a connection; others attach via skills");
        }
        StringBuilder url = new StringBuilder(frontendBaseUrl)
                .append("/connections/new?connector=")
                .append(URLEncoder.encode(code, StandardCharsets.UTF_8));
        String displayName = blankToNull(name);
        if (displayName != null) {
            url.append("&name=").append(URLEncoder.encode(displayName, StandardCharsets.UTF_8));
        }
        return new ConnectionSetup("setup_required", url.toString(), code);
    }

    @Tool(name = "bind_connection",
            description = "Bind an existing connection to an agent so the agent can use its tools. "
                    + "Only for integration connectors (create the connection first via create_connection)",
            annotations = @ToolAnnotations(destructiveHint = false, openWorldHint = false))
    public OperationResult bindConnection(
            @ToolParam("Agent public ID") String agentId,
            @ToolParam("Connection ID (from list_connections)") String connectionId) {
        UUID agent = parseUuid(agentId, "agentId");
        requireNotSelf(agent);
        UUID connection = parseUuid(connectionId, "connectionId");
        domain(() -> connectionBindingService.bindAndView(userId(), agent, connection));
        return new OperationResult(true, "Connection bound to agent");
    }

    // ---- helpers ---------------------------------------------------------------------------

    private SkillBrief toSkillBrief(Skill skill) {
        return new SkillBrief(skill.getId().toString(), skill.getName(), displayTitle(skill),
                skill.getDescription(), skill.getConnectorCodes(), skill.getVersion(),
                Boolean.TRUE.equals(skill.getIsPublic()), isSystem(skill));
    }

    private static String displayTitle(Skill skill) {
        return skill.getTitle() != null ? skill.getTitle() : skill.getName();
    }

    private AgentBrief toAgentBrief(Agent agent) {
        return new AgentBrief(agent.getId().toString(), agent.getName(), agent.getDescription(),
                agent.getType().name(), agent.isEnabled(),
                agent.getAgenticTeamId() == null ? null : agent.getAgenticTeamId().toString());
    }

    private ConnectionBrief toConnectionBrief(Connection connection) {
        return new ConnectionBrief(connection.getId().toString(), connection.getConnectorCode(),
                connection.getName(), Boolean.TRUE.equals(connection.getEnabled()), connection.getSubCode(),
                connection.getAuthStatus().name());
    }

    private static boolean isSystem(Skill skill) {
        return SystemSkillBootstrap.SYSTEM_USER_ID.equals(skill.getUserId());
    }

    private Agent ownedAgent(UUID id) {
        return agentRepository.findById(id)
                .filter(a -> a.getUserId().equals(userId()))
                .orElseThrow(() -> new ConnectorException("Agent not found: " + id));
    }

    private Skill accessibleSkill(UUID id) {
        Skill skill = skillRepository.findByIdNotDeleted(id)
                .orElseThrow(() -> new ConnectorException("Skill not found: " + id));
        if (!skill.getUserId().equals(userId()) && !Boolean.TRUE.equals(skill.getIsPublic())) {
            throw new ConnectorException("Skill not found: " + id);
        }
        return skill;
    }

    private AgentType parseAgentType(String type) {
        String value = blankToNull(type);
        if (value == null) {
            return AgentType.GENERIC;
        }
        AgentType parsed;
        try {
            parsed = AgentType.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ConnectorException("Invalid agent type: '" + type + "'. Allowed: GENERIC, CENTRIFUGO");
        }
        if (parsed == AgentType.WEBHOOK) {
            throw new ConnectorException("WEBHOOK agents require webhook configuration — create them in the UI");
        }
        return parsed;
    }

    /** An agent does not manage itself: the target equals the caller. */
    private void requireNotSelf(UUID targetAgentId) {
        if (targetAgentId.equals(ConnectorEnvHolder.current().agentId())) {
            throw new ConnectorException("An agent cannot manage itself");
        }
    }

    private UUID userId() {
        UUID userId = ConnectorEnvHolder.current().userId();
        if (userId == null) {
            throw new ConnectorException("No user bound to the platform call");
        }
        return userId;
    }

    /** Run a domain operation, translating the core's HTTP exceptions into {@link ConnectorException}. */
    private <T> T domain(Supplier<T> op) {
        try {
            return op.get();
        } catch (BaseHttpStatusException e) {
            throw new ConnectorException(e.getMessage());
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String requireText(String value, String field) {
        String v = blankToNull(value);
        if (v == null) {
            throw new ConnectorException("Parameter '" + field + "' is required");
        }
        return v;
    }

    private static UUID parseUuid(String value, String field) {
        String v = requireText(value, field);
        try {
            return UUID.fromString(v);
        } catch (IllegalArgumentException e) {
            throw new ConnectorException("Invalid " + field + ": '" + value + "'");
        }
    }
}
