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
import ru.agimate.controlapi.connectors.core.annotation.Tool;
import ru.agimate.controlapi.connectors.core.annotation.ToolAnnotations;
import ru.agimate.controlapi.connectors.core.annotation.ToolParam;
import ru.agimate.controlapi.connectors.internal.platform.dto.PlatformAgentDtos.AgentDetail;
import ru.agimate.controlapi.connectors.internal.platform.dto.PlatformAgentDtos.AgentKeyRegenerated;
import ru.agimate.controlapi.connectors.internal.platform.dto.PlatformAgentDtos.AgentList;
import ru.agimate.controlapi.connectors.internal.platform.dto.PlatformAgentDtos.AgentSkillBinding;
import ru.agimate.controlapi.connectors.internal.platform.dto.PlatformAgentDtos.AgentSkillList;
import ru.agimate.controlapi.connectors.internal.platform.dto.PlatformAgentDtos.BoundSkill;
import ru.agimate.controlapi.connectors.internal.platform.dto.PlatformAgentDtos.CreatedAgent;
import ru.agimate.controlapi.connectors.internal.platform.dto.PlatformAgentDtos.FileBrief;
import ru.agimate.controlapi.connectors.internal.platform.dto.PlatformAgentDtos.FileList;
import ru.agimate.controlapi.connectors.internal.platform.dto.PlatformAgentDtos.SkillBrief;
import ru.agimate.controlapi.connectors.internal.platform.dto.PlatformAgentDtos.SkillDetail;
import ru.agimate.controlapi.connectors.internal.platform.dto.PlatformAgentDtos.SkillList;
import ru.agimate.controlapi.connectors.internal.platform.dto.PlatformDtos.AgentBrief;
import ru.agimate.controlapi.connectors.internal.platform.dto.PlatformDtos.OperationResult;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.entities.AgentSkill;
import ru.agimate.controlapi.database.entities.Skill;
import ru.agimate.controlapi.database.entities.StoredFile;
import ru.agimate.controlapi.database.enums.AgentType;
import ru.agimate.controlapi.database.repositories.AgentRepository;
import ru.agimate.controlapi.database.repositories.AgentSkillRepository;
import ru.agimate.controlapi.database.repositories.SkillRepository;
import ru.agimate.controlapi.database.repositories.SkillSpecs;
import ru.agimate.controlapi.database.repositories.StoredFileRepository;
import ru.agimate.controlapi.service.AgentService;
import ru.agimate.controlapi.service.AgentSkillService;
import ru.agimate.controlapi.service.SkillService;
import ru.agimate.controlapi.service.SystemSkillBootstrap;
import ru.agimate.controlapi.service.dto.agent.AgentCreateCommand;
import ru.agimate.controlapi.service.dto.agent.AgentUpdateCommand;
import ru.agimate.controlapi.service.connection.ConnectionBindingService;
import ru.agimate.controlapi.service.file.UserFileService;
import ru.agimate.controlapi.storage.FileIds;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Tools of the platform connector's agents module — the meta-agent manages agents, skills and files
 * on behalf of its human owner ({@code env.userId}). A thin adapter: reads come from the
 * repositories, writes go through the existing services (command overloads, so as not to drag in
 * {@code controller/**}). Domain {@link BaseHttpStatusException}s are translated into
 * {@link ru.agimate.controlapi.connectors.core.ConnectorException} so the message reaches the agent.
 * Shared guards and parsing live in
 * {@link PlatformToolsSupport} (an agent does not manage itself — {@code requireNotSelf}).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlatformAgentToolService {

    @Value("${app.frontend.base-url}")
    private String frontendBaseUrl;

    private final AgentRepository agentRepository;
    private final AgentSkillRepository agentSkillRepository;
    private final SkillRepository skillRepository;
    private final StoredFileRepository storedFileRepository;
    private final AgentService agentService;
    private final SkillService skillService;
    private final AgentSkillService agentSkillService;
    private final UserFileService userFileService;
    private final ConnectionBindingService connectionBindingService;

    // ---- agents & skills -------------------------------------------------------------------

    @Tool(name = "list_agents", description = "List the agents you own",
            annotations = @ToolAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = false))
    public AgentList listAgents(
            @ToolParam(value = "Optional search over agent name/description", required = false) String search) {
        String q = PlatformToolsSupport.blankToNull(search);
        var page = PageRequest.of(0, PlatformToolsSupport.MAX_LISTING, Sort.by("name").ascending());
        var agents = (q != null
                ? agentRepository.searchForUser(PlatformToolsSupport.userId(), null, q, page)
                : agentRepository.findByUserId(PlatformToolsSupport.userId(), page));
        var listed = agents.map(this::toAgentBrief).getContent();
        return new AgentList(listed, PlatformToolsSupport.truncated(agents));
    }

    @Tool(name = "get_agent",
            description = "Get an agent's full config: instructions, type, team and bound skills (with the "
                    + "connectors each skill requires). Cross-reference list_connections to see which "
                    + "required connectors still need a connection",
            annotations = @ToolAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = false))
    public AgentDetail getAgent(@ToolParam("Agent public ID") String agentId) {
        Agent agent = PlatformToolsSupport.ownedAgent(agentRepository,
                PlatformToolsSupport.parseUuid(agentId, "agentId"));
        List<UUID> skillIds = agentSkillRepository.findByAgentId(agent.getId()).stream()
                .map(AgentSkill::getSkillId)
                .toList();
        List<BoundSkill> skills = skillIds.isEmpty() ? List.of()
                : skillRepository.findByIdInNotDeleted(skillIds).stream()
                .map(s -> new BoundSkill(s.getId().toString(), s.getName(), s.getConnectorCodes()))
                .toList();
        return new AgentDetail(agent.getId().toString(), agent.getName(), agent.getDescription(),
                agent.getInstructions(), agent.getType().name(), agent.isEnabled(),
                agent.getAgenticTeamId() == null ? null : agent.getAgenticTeamId().toString(),
                agent.getWebhookUrl(), agent.hasWebhookAuth(), skills);
    }

    @Tool(name = "create_agent",
            description = "Create a new agent. type: GENERIC (default), CENTRIFUGO, MCP or WEBHOOK. "
                    + "MCP means the brain lives outside the platform. WEBHOOK requires webhookUrl. teamId puts "
                    + "the agent into an agentic team you own. skillIds binds skills at creation (their internal "
                    + "connectors are opened for the agent; a skill declaring an external connector needs its "
                    + "instance chosen first). Returns the agent's id and a link where the key is shown once in "
                    + "the UI — secrets are never handled here: a webhook auth header, if the endpoint needs "
                    + "one, is entered by the user on the agent page the link opens",
            annotations = @ToolAnnotations(destructiveHint = false, openWorldHint = false))
    public CreatedAgent createAgent(
            @ToolParam("Agent name") String name,
            @ToolParam(value = "Agent description", required = false) String description,
            @ToolParam(value = "System instructions / persona", required = false) String instructions,
            @ToolParam(value = "Agent type: GENERIC (default), CENTRIFUGO, MCP or WEBHOOK", required = false)
            String type,
            @ToolParam(value = "Webhook URL — required when type=WEBHOOK", required = false) String webhookUrl,
            @ToolParam(value = "Agentic team public ID to place the agent in", required = false) String teamId,
            @ToolParam(value = "Skill public IDs to bind on creation", required = false) List<String> skillIds) {
        AgentType agentType = PlatformToolsSupport.parseAgentType(type);
        List<UUID> skills = skillIds == null ? null : skillIds.stream()
                .map(id -> PlatformToolsSupport.parseUuid(id, "skillIds")).toList();
        // WEBHOOK validation (webhookUrl required) is the service's job — the connector only blanks
        // empty strings and lets AgentService.validateWebhookFields refuse a WEBHOOK without an address.
        // webhookAuthHeader is deliberately absent: a secret never travels through the model — the
        // user enters it on the agent page (the same page the returned keyUrl opens).
        var command = new AgentCreateCommand(PlatformToolsSupport.requireText(name, "name"),
                PlatformToolsSupport.blankToNull(description), PlatformToolsSupport.blankToNull(instructions),
                agentType, PlatformToolsSupport.blankToNull(webhookUrl), null,
                PlatformToolsSupport.parseUuidOrNull(teamId, "teamId"), skills, null);
        var result = PlatformToolsSupport.domain(() -> agentService.create(PlatformToolsSupport.userId(), command));
        // The same second step the UI performs after creating an agent from a preset: a skill binding
        // is a declaration, and the agent only reaches the skill's tools once its connectors are open.
        // Internal connectors have exactly one instance per user, so the server opens them without
        // asking; external ones need an instance chosen (bind_connection) and stay a declaration.
        if (skills != null) {
            PlatformToolsSupport.domain(() -> {
                openInternalSkillConnectors(result.agent().getId(), skills);
                return null;
            });
        }
        return new CreatedAgent(result.agent().getId().toString(), result.agent().getName(),
                frontendBaseUrl + "/dashboard/agents/" + result.agent().getId());
    }

    @Tool(name = "update_agent",
            description = "Update an agent: name, description, instructions, type, webhookUrl, enabled. "
                    + "Omitted params are left unchanged; an empty string clears a "
                    + "string field except type, where an empty value means \"not sent\" (a type has no "
                    + "cleared state). Switching type away from WEBHOOK clears webhookUrl and the stored "
                    + "webhook auth header on the server. The auth header itself is never a tool parameter — "
                    + "the user enters it on the agent page the keyUrl opens (replace only; clearing it "
                    + "without leaving WEBHOOK is a UI action, not a tool one). "
                    + "Changing type to a non-push type is refused while the agent has channels",
            annotations = @ToolAnnotations(destructiveHint = true, openWorldHint = false))
    public AgentDetail updateAgent(
            @ToolParam("Agent public ID") String agentId,
            @ToolParam(value = "New name (empty string is rejected)", required = false) String name,
            @ToolParam(value = "New description; empty string clears", required = false) String description,
            @ToolParam(value = "New instructions; empty string clears", required = false) String instructions,
            @ToolParam(value = "New type: GENERIC, CENTRIFUGO, MCP or WEBHOOK", required = false) String type,
            @ToolParam(value = "New webhook URL; empty string clears (WEBHOOK requires it)", required = false)
            String webhookUrl,
            @ToolParam(value = "Enable or disable the agent", required = false) Boolean enabled) {
        UUID id = PlatformToolsSupport.parseUuid(agentId, "agentId");
        PlatformToolsSupport.requireNotSelf(id);
        PlatformToolsSupport.ownedAgent(agentRepository, id);
        // PATCH semantics live in AgentService.patch: null = "not sent" (keep), blank = explicit erase,
        // and the webhook pair is normalized there (leaving WEBHOOK clears the address and the header).
        // Raw strings go through untouched — only the type enum is parsed here. A blank type is "not
        // sent" on the update path (there is no "clear" for a type): parseAgentType's blank→GENERIC
        // default belongs to create_agent alone — applying it here would silently rewrite a WEBHOOK
        // agent into GENERIC and wipe its webhook configuration. The auth header is always "not sent":
        // it is entered by the user on the agent page, never through the model.
        var command = new AgentUpdateCommand(name, description, instructions,
                type != null && !type.isBlank() ? PlatformToolsSupport.parseAgentType(type) : null,
                webhookUrl, null, enabled);
        PlatformToolsSupport.domain(() -> agentService.patch(id, PlatformToolsSupport.userId(), command));
        return getAgent(agentId);
    }

    @Tool(name = "delete_agent",
            description = "Delete an agent (soft delete). Its bindings and access policies are removed, "
                    + "its scheduled jobs are cancelled; runs and sessions remain in history but the agent "
                    + "disappears from listings. Cannot be used on the agent that is calling",
            annotations = @ToolAnnotations(destructiveHint = true, openWorldHint = false))
    public OperationResult deleteAgent(@ToolParam("Agent public ID") String agentId) {
        UUID id = PlatformToolsSupport.parseUuid(agentId, "agentId");
        PlatformToolsSupport.requireNotSelf(id);
        PlatformToolsSupport.domain(() -> {
            agentService.delete(id, PlatformToolsSupport.userId());
            return null;
        });
        return new OperationResult(true, "Agent deleted");
    }

    @Tool(name = "regenerate_agent_key",
            description = "Regenerate an agent's access key. The new key is shown once in the UI at the "
                    + "returned link; the old key stops working immediately. Secrets are never returned by tools. "
                    + "Cannot be used on the agent that is calling",
            annotations = @ToolAnnotations(destructiveHint = true, openWorldHint = false))
    public AgentKeyRegenerated regenerateAgentKey(@ToolParam("Agent public ID") String agentId) {
        UUID id = PlatformToolsSupport.parseUuid(agentId, "agentId");
        PlatformToolsSupport.requireNotSelf(id);
        var result = PlatformToolsSupport.domain(
                () -> agentService.regenerateKey(id, PlatformToolsSupport.userId()));
        return new AgentKeyRegenerated(result.agent().getId().toString(), result.agent().getName(),
                frontendBaseUrl + "/dashboard/agents/" + result.agent().getId());
    }

    @Tool(name = "list_agent_skills",
            description = "List the skills bound to an agent, with the satisfaction status of each: "
                    + "whether every connector the skill declares has a bound connection. An unsatisfied skill "
                    + "is not given to the agent. Use list_agent_connections to see what is missing. "
                    + "Newest bindings first, first 100",
            annotations = @ToolAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = false))
    public AgentSkillList listAgentSkills(@ToolParam("Agent public ID") String agentId) {
        UUID id = PlatformToolsSupport.parseUuid(agentId, "agentId");
        // A read-only listing of your own skills is legitimate (get_agent has no self-guard either);
        // the guard is about managing, not about looking.
        PlatformToolsSupport.ownedAgent(agentRepository, id);
        // Paged the same way the manage API pages the same rows (createdAt DESC) — the connector's
        // page cap is MAX_LISTING, and truncation is reported on the record.
        var page = agentSkillRepository.findByAgentId(id, PageRequest.of(0,
                PlatformToolsSupport.MAX_LISTING, Sort.by("createdAt").descending()));
        List<UUID> skillIds = page.getContent().stream()
                .map(AgentSkill::getSkillId)
                .toList();
        Map<UUID, Skill> skillsById = skillIds.isEmpty() ? Map.of()
                : skillRepository.findByIdInNotDeleted(skillIds).stream()
                .collect(Collectors.toMap(Skill::getId, s -> s));
        // Satisfaction is the service's computation: satisfiedSkillInstances resolves every bound skill
        // against the agent's connections and reports only the complete ones (skillId → bound instance
        // ids) — exactly "every declared connector has a bound connection". A skill absent from the map
        // is unsatisfied and is not given to the agent. It is computed over all bindings — only the
        // response is paged.
        Map<UUID, Set<UUID>> satisfiedInstances = agentSkillService.satisfiedSkillInstances(id);
        List<AgentSkillBinding> items = skillIds.stream()
                .map(skillsById::get)
                .filter(Objects::nonNull)
                .map(s -> new AgentSkillBinding(s.getId().toString(), s.getName(), s.getConnectorCodes(),
                        satisfiedInstances.containsKey(s.getId())))
                .toList();
        return new AgentSkillList(items, PlatformToolsSupport.truncated(page));
    }

    @Tool(name = "delete_skill",
            description = "Delete one of your skills (soft delete). All bindings to agents are removed. "
                    + "System skills are refused outright (they are seeded by the platform)",
            annotations = @ToolAnnotations(destructiveHint = true, openWorldHint = false))
    public OperationResult deleteSkill(@ToolParam("Skill public ID") String skillId) {
        UUID id = PlatformToolsSupport.parseUuid(skillId, "skillId");
        // Own or public — an accessible skill. The non-admin service path (admin=false) then refuses
        // everything the caller does not own, including system skills and other users' public ones.
        PlatformToolsSupport.accessibleSkill(skillRepository, id);
        PlatformToolsSupport.domain(() -> {
            skillService.delete(id, PlatformToolsSupport.userId(), false);
            return null;
        });
        return new OperationResult(true, "Skill deleted");
    }

    @Tool(name = "delete_file",
            description = "Delete one of the owner's files ahead of its TTL (an agf_ id from list_files). "
                    + "References to it stop resolving, exactly as on expiry",
            annotations = @ToolAnnotations(destructiveHint = true, openWorldHint = false))
    public OperationResult deleteFile(@ToolParam("File id (agf_…) from list_files") String fileId) {
        PlatformToolsSupport.domain(() -> {
            userFileService.delete(PlatformToolsSupport.userId(),
                    PlatformToolsSupport.requireText(fileId, "fileId"));
            return null;
        });
        return new OperationResult(true, "File deleted");
    }

    @Tool(name = "mark_skills_installed",
            description = "Accept the current version of every skill bound to an agent and clear their "
                    + "needsReinstall marks. Call after the agent's skills were updated out-of-band",
            annotations = @ToolAnnotations(destructiveHint = false, openWorldHint = false))
    public OperationResult markSkillsInstalled(@ToolParam("Agent public ID") String agentId) {
        UUID id = PlatformToolsSupport.parseUuid(agentId, "agentId");
        PlatformToolsSupport.requireNotSelf(id);
        PlatformToolsSupport.domain(() -> {
            agentSkillService.markSkillsInstalled(id, PlatformToolsSupport.userId());
            return null;
        });
        return new OperationResult(true, "Skills marked as installed");
    }

    @Tool(name = "list_skills",
            description = "List skills. scope=MINE (your own, default) or PUBLIC (shared catalog). "
                    + "A skill is a SKILL.md document; use get_skill for its full body",
            annotations = @ToolAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = false))
    public SkillList listSkills(
            @ToolParam(value = "MINE or PUBLIC (default MINE)", required = false) String scope,
            @ToolParam(value = "Optional search over name/description", required = false) String search,
            @ToolParam(value = "Filter by required connector code", required = false) String connectorCode) {
        String scopeValue = PlatformToolsSupport.blankToNull(scope);
        if (scopeValue != null && !"MINE".equalsIgnoreCase(scopeValue) && !"PUBLIC".equalsIgnoreCase(scopeValue)) {
            throw new ConnectorException("Invalid scope: '" + scope + "'. Allowed: MINE, PUBLIC");
        }
        Specification<Skill> spec = "PUBLIC".equalsIgnoreCase(scopeValue)
                ? SkillSpecs.isPublic()
                : SkillSpecs.ownedBy(PlatformToolsSupport.userId());
        spec = spec.and(SkillSpecs.notDeleted());
        String searchQ = PlatformToolsSupport.blankToNull(search);
        if (searchQ != null) {
            spec = spec.and(SkillSpecs.searchByNameOrDescription(searchQ));
        }
        String connQ = PlatformToolsSupport.blankToNull(connectorCode);
        if (connQ != null) {
            spec = spec.and(SkillSpecs.hasConnector(connQ));
        }
        var page = skillRepository.findAll(spec, PageRequest.of(0,
                PlatformToolsSupport.MAX_LISTING, Sort.by("name").ascending()));
        List<SkillBrief> items = page.map(this::toSkillBrief).getContent();
        return new SkillList(items, PlatformToolsSupport.truncated(page));
    }

    @Tool(name = "get_skill", description = "Get a skill's full SKILL.md body and metadata",
            annotations = @ToolAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = false))
    public SkillDetail getSkill(@ToolParam("Skill public ID") String skillId) {
        Skill skill = PlatformToolsSupport.accessibleSkill(skillRepository,
                PlatformToolsSupport.parseUuid(skillId, "skillId"));
        return new SkillDetail(skill.getId().toString(), skill.getName(), displayTitle(skill),
                skill.getDescription(), skill.getConnectorCodes(), skill.getVersion(),
                Boolean.TRUE.equals(skill.getIsPublic()), isSystem(skill), skill.getMdContent());
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
        Skill created = PlatformToolsSupport.domain(() -> skillService.create(PlatformToolsSupport.userId(),
                PlatformToolsSupport.requireText(skillMd, "skillMd"), Boolean.TRUE.equals(isPublic)));
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
        UUID id = PlatformToolsSupport.parseUuid(skillId, "skillId");
        PlatformToolsSupport.domain(() -> skillService.update(id, PlatformToolsSupport.userId(), false,
                PlatformToolsSupport.requireText(skillMd, "skillMd"), isPublic));
        return getSkill(skillId);
    }

    @Tool(name = "bind_skill",
            description = "Bind a skill to an agent. The skill's internal connectors are opened for the "
                    + "agent automatically; a skill declaring an external connector needs its instance "
                    + "chosen first (list_connections / create_connection) and bound with bind_connection",
            annotations = @ToolAnnotations(destructiveHint = false, openWorldHint = false))
    public OperationResult bindSkill(
            @ToolParam("Agent public ID") String agentId,
            @ToolParam("Skill public ID") String skillId) {
        UUID agent = PlatformToolsSupport.parseUuid(agentId, "agentId");
        PlatformToolsSupport.requireNotSelf(agent);
        UUID skill = PlatformToolsSupport.parseUuid(skillId, "skillId");
        PlatformToolsSupport.domain(() -> {
            agentSkillService.create(agent, skill, PlatformToolsSupport.userId());
            // The UI's bind flow opens the skill's connectors as a separate step; the tool does both.
            openInternalSkillConnectors(agent, List.of(skill));
            return null;
        });
        return new OperationResult(true, "Skill bound to agent; its internal connectors are open");
    }

    @Tool(name = "unbind_skill", description = "Unbind a skill from an agent",
            annotations = @ToolAnnotations(destructiveHint = true, openWorldHint = false))
    public OperationResult unbindSkill(
            @ToolParam("Agent public ID") String agentId,
            @ToolParam("Skill public ID") String skillId) {
        UUID agent = PlatformToolsSupport.parseUuid(agentId, "agentId");
        PlatformToolsSupport.requireNotSelf(agent);
        UUID skill = PlatformToolsSupport.parseUuid(skillId, "skillId");
        PlatformToolsSupport.domain(() -> {
            agentSkillService.delete(agent, skill, PlatformToolsSupport.userId());
            return null;
        });
        return new OperationResult(true, "Skill unbound from agent");
    }

    // ---- files ----------------------------------------------------------------------------

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
        var page = storedFileRepository.findVisible(PlatformToolsSupport.userId(), null,
                sessionId, PlatformToolsSupport.blankToNull(name), LocalDateTime.now(),
                PageRequest.of(0, PlatformToolsSupport.MAX_LISTING));
        List<FileBrief> briefs = page.getContent().stream()
                .map(PlatformAgentToolService::toFileBrief)
                .toList();
        return new FileList(briefs, PlatformToolsSupport.truncated(page));
    }


    /**
     * Opens the internal connectors a set of skills declares, mirroring the UI's {@code openAgentAccess}
     * step: for an INTERNAL connector the mode row is the single legal instance, so the server opens
     * it without asking; EXTERNAL connectors need a user-chosen instance and UNKNOWN codes have no
     * instance at all — both stay closed, and the skill reads as unsatisfied until bound explicitly.
     */
    private void openInternalSkillConnectors(UUID agentId, List<UUID> skillIds) {
        UUID userId = PlatformToolsSupport.userId();
        for (UUID skillId : skillIds) {
            skillRepository.findByIdNotDeleted(skillId).ifPresent(skill -> {
                for (String code : skill.getConnectorCodes()) {
                    if (connectionBindingService.kindOf(code) == ConnectionBindingService.ConnectorKind.INTERNAL) {
                        connectionBindingService.bindInternal(userId, agentId, code);
                    }
                }
            });
        }
    }


    private static FileBrief toFileBrief(StoredFile file) {
        return new FileBrief(FileIds.external(file.getId()), file.getName(), file.getMime(),
                file.getSizeBytes(),
                file.getCreatedAt() != null ? file.getCreatedAt().toString() : null);
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

    private static boolean isSystem(Skill skill) {
        return SystemSkillBootstrap.SYSTEM_USER_ID.equals(skill.getUserId());
    }
}
