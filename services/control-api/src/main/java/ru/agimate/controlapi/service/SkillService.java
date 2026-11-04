package ru.agimate.controlapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.PageRequests;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.ConflictStatusException;
import ru.agimate.common.rest.error.ForbiddenStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.controlapi.abac.SkillPolicySync;
import ru.agimate.controlapi.connectors.core.ConnectorRegistry;
import ru.agimate.controlapi.connectors.core.IntegrationConnectorHandler;
import ru.agimate.controlapi.connectors.core.dto.CredentialField;
import ru.agimate.controlapi.controller.manage.dto.AgentSummaryResponse;
import ru.agimate.controlapi.controller.manage.dto.CreateSkillRequest;
import ru.agimate.controlapi.controller.manage.dto.SkillDetailResponse;
import ru.agimate.controlapi.controller.manage.dto.SkillListScope;
import ru.agimate.controlapi.controller.manage.dto.SkillOriginResponse;
import ru.agimate.controlapi.controller.manage.dto.SkillResponse;
import ru.agimate.controlapi.controller.manage.dto.UpdateSkillConnectorsRequest;
import ru.agimate.controlapi.controller.manage.dto.UpdateSkillRequest;
import ru.agimate.controlapi.database.entities.App;
import ru.agimate.controlapi.database.entities.Connection;
import ru.agimate.controlapi.database.entities.Skill;
import ru.agimate.controlapi.database.enums.ContentCategory;
import ru.agimate.controlapi.database.enums.ContentTag;
import ru.agimate.controlapi.database.enums.Disclosure;
import ru.agimate.controlapi.database.enums.SkillUpdatedBy;
import ru.agimate.controlapi.database.model.AppSkill;
import ru.agimate.controlapi.database.model.ConnectorRequirement;
import ru.agimate.controlapi.database.repositories.AgentPresetRepository;
import ru.agimate.controlapi.database.repositories.AgentRepository;
import ru.agimate.controlapi.database.repositories.AgentSkillRepository;
import ru.agimate.controlapi.database.repositories.AppRepository;
import ru.agimate.controlapi.database.repositories.ConnectionRepository;
import ru.agimate.controlapi.database.repositories.ConnectorRepository;
import ru.agimate.controlapi.database.repositories.SkillRepository;
import ru.agimate.controlapi.database.repositories.SkillSpecs;
import ru.agimate.controlapi.database.enums.ContentLanguage;
import ru.agimate.controlapi.service.seed.ContentLanguageResolver;
import ru.agimate.controlapi.util.ConnectorRequirements;
import ru.agimate.controlapi.util.SkillFrontmatterParser;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SkillService {

    /** The connector code every app instance has; an app skill declares only it. */
    public static final String APP_CONNECTOR_CODE = "app";

    /** A skill's {@code name} is a machine code: lower-case latin letters and digits joined by hyphens (kebab-case). */
    static final java.util.regex.Pattern NAME_SLUG =
            java.util.regex.Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");

    private final SkillRepository skillRepository;
    private final AppRepository appRepository;
    private final AgentRepository agentRepository;
    private final AgentSkillRepository agentSkillRepository;
    private final AgentPresetRepository agentPresetRepository;
    private final ConnectorRepository connectorRepository;
    private final ConnectionRepository connectionRepository;
    private final ConnectorRegistry connectorRegistry;
    private final SkillPolicySync skillPolicySync;
    private final ContentLanguageResolver contentLanguageResolver;

    /**
     * @param connectionId the user's connection the skills must fit; exclusive with {@code connectorCode},
     *                     which the connection already names
     */
    public Page<SkillResponse> getSkills(UUID userId, SkillListScope scope, String search, String connectorCode,
                                         UUID connectionId, ContentCategory category, ContentTag tag,
                                         int page, int size) {
        Specification<Skill> base = switch (scope) {
            case MINE -> SkillSpecs.ownedBy(userId);
            case PUBLIC -> SkillSpecs.isPublic().and(SkillSpecs.inCatalogue(contentLanguageResolver.requestLanguage()));
            case ALL -> SkillSpecs.visibleTo(userId, contentLanguageResolver.requestLanguage());
        };
        if (connectionId != null) {
            if (connectorCode != null && !connectorCode.isBlank()) {
                throw new BadRequestStatusException("connectionId and connectorCode are exclusive: "
                        + "the connection already names its connector");
            }
            base = base.and(fitting(connectionId, userId));
        }
        return findSkills(base, search, connectorCode, category, tag, page, size);
    }

    /** Skills that fit one of the user's connections; 404 for a connection that is not theirs or is gone. */
    public Specification<Skill> fitting(UUID connectionId, UUID userId) {
        connectionRepository.findByIdNotDeleted(connectionId)
                .filter(connection -> connection.getUserId().equals(userId))
                .orElseThrow(() -> new NotFoundStatusException("Connection not found"));
        return SkillSpecs.fitsConnection(connectionId);
    }

    public SkillDetailResponse getSkillDetail(UUID id, UUID userId) {
        Skill skill = findAccessibleSkill(id, userId);
        return SkillDetailResponse.from(skill, originOf(skill));
    }

    public Page<AgentSummaryResponse> getSkillAgents(UUID id, UUID userId, String search, int page, int size) {
        findAccessibleSkill(id, userId);
        String normalizedSearch = (search == null || search.isBlank()) ? null : search.trim();
        PageRequest pageRequest = PageRequests.of(page, size, Sort.by("name").ascending());
        return agentRepository.findBySkillId(id, userId, normalizedSearch, pageRequest)
                .map(AgentSummaryResponse::from);
    }

    @Transactional
    public SkillResponse create(UUID userId, CreateSkillRequest request) {
        return SkillResponse.from(doCreate(userId, request.resolveIsPublic(), request.skillMd()));
    }

    /** Service-layer overload (the connector layer): a skill from a ready SKILL.md, returning the entity
     * (no controller DTO inside a connector). */
    @Transactional
    public Skill create(UUID userId, String skillMd, boolean isPublic) {
        return doCreate(userId, isPublic, skillMd);
    }

    /**
     * Create a system skill (owned by {@link SystemSkillBootstrap#SYSTEM_USER_ID}, always public so it
     * can be bound to other people's agents). ADMIN-only at the controller level.
     */
    @Transactional
    public SkillResponse createSystem(CreateSkillRequest request) {
        return SkillResponse.from(doCreate(SystemSkillBootstrap.SYSTEM_USER_ID, true, request.skillMd()));
    }

    private Skill doCreate(UUID ownerId, boolean isPublic, String skillMd) {
        SkillFrontmatterParser.ParsedSkill parsed = SkillFrontmatterParser.parse(skillMd);
        validateName(parsed.name());
        List<ConnectorRequirement> connectors = validateConnectors(parsed.connectors());

        // A system skill belongs to the catalogue the admin is looking at; a user's skill to none — it is in
        // every one.
        ContentLanguage language = isSystemOwner(ownerId) ? contentLanguageResolver.requestLanguage() : null;
        if (skillRepository.existsByKeyNotDeleted(ownerId, parsed.name(), language)) {
            throw new ConflictStatusException("Skill with name '" + parsed.name() + "' already exists");
        }

        Skill skill = Skill.builder()
                .name(parsed.name())
                .language(language)
                .title(parsed.title())
                .description(parsed.description())
                .mdContent(parsed.body())
                .connectors(connectors)
                .disclosure(parsed.disclosure())
                .category(parsed.category())
                .tags(new ArrayList<>(parsed.tags()))
                .starters(new ArrayList<>(parsed.starters()))
                .userId(ownerId)
                .isPublic(isPublic)
                .build();

        try {
            skill = skillRepository.save(skill);
        } catch (DataIntegrityViolationException e) {
            throw new ConflictStatusException("Skill with name '" + parsed.name() + "' already exists");
        }

        log.info("Created skill '{}' id={} for owner={}", skill.getName(), skill.getId(), ownerId);
        return skill;
    }

    @Transactional
    public SkillResponse update(UUID id, UUID userId, boolean admin, UpdateSkillRequest request) {
        return response(update(id, userId, admin, request.skillMd(), request.isPublic()));
    }

    /**
     * Service-layer overload (the connector layer): editing SKILL.md, returning the entity (no controller DTO).
     *
     * @param isPublic {@code null} keeps the current visibility — this endpoint replaces the document, and a
     *                 caller editing only the body must not unpublish the skill by omission
     */
    @Transactional
    public Skill update(UUID id, UUID userId, boolean admin, String skillMd, Boolean isPublic) {
        Skill skill = findOwnedOrSystemAdmin(id, userId, admin);
        boolean system = isSystem(skill);

        SkillFrontmatterParser.ParsedSkill parsed = SkillFrontmatterParser.parse(skillMd);
        validateName(parsed.name());
        List<ConnectorRequirement> connectors = validateConnectors(parsed.connectors());
        validateOriginEdit(skill, parsed.name(), connectors, isPublic);

        if (!skill.getName().equals(parsed.name())) {
            if (system) {
                // A system skill's name is the key of every reference to it: (SYSTEM_USER_ID, name, language) in the
                // seeder and in preset.skill_names. Renaming would orphan those references — so we forbid it.
                throw new BadRequestStatusException("System skill cannot be renamed");
            }
            if (skillRepository.existsByKeyNotDeleted(skill.getUserId(), parsed.name(), skill.getLanguage())) {
                throw new ConflictStatusException("Skill with name '" + parsed.name() + "' already exists");
            }
        }

        skill.setName(parsed.name());
        skill.setTitle(parsed.title());
        skill.setDescription(parsed.description());
        skill.setMdContent(parsed.body());
        skill.setConnectors(connectors);
        skill.setDisclosure(parsed.disclosure());
        skill.setCategory(parsed.category());
        skill.setTags(new ArrayList<>(parsed.tags()));
        skill.setStarters(new ArrayList<>(parsed.starters()));
        if (isPublic != null) {
            skill.setIsPublic(isPublic);
        }
        // The app keeps the skill fresh only until the first user edit: after this the text is the
        // user's, even when they changed nothing but the access rules.
        if (skill.getOriginConnectionId() != null) {
            skill.setUpdatedBy(SkillUpdatedBy.USER);
        }
        skill.setVersion(skill.getVersion() + 1);

        try {
            skill = skillRepository.save(skill);
        } catch (DataIntegrityViolationException e) {
            throw new ConflictStatusException("Skill with name '" + parsed.name() + "' already exists");
        }

        log.info("Updated skill '{}' id={} version={}", skill.getName(), id, skill.getVersion());
        return skill;
    }

    /**
     * Replace a skill's connector requirements in place (without touching the body or the name). The
     * rights are the same as for a full {@link #update}: one's own skill, or a system one for ADMIN. A
     * skill only declares what it needs and never binds anything, so a new connector reaches no
     * already-bound agent by itself — the version bump is what surfaces the drift, against
     * {@code AgentSkill.installedSkillVersion}.
     */
    @Transactional
    public SkillResponse updateConnectors(UUID id, UUID userId, boolean admin, UpdateSkillConnectorsRequest request) {
        Skill skill = findOwnedOrSystemAdmin(id, userId, admin);
        List<ConnectorRequirement> connectors = validateConnectors(
                ConnectorRequirements.normalize(request.resolveConnectors()));
        validateOriginConnectors(skill, connectors);

        skill.setConnectors(connectors);
        // The same rule as a full edit: changing only the requirement rules still makes the skill the
        // user's, and the app stops overwriting its text.
        if (skill.getOriginConnectionId() != null) {
            skill.setUpdatedBy(SkillUpdatedBy.USER);
        }
        skill.setVersion(skill.getVersion() + 1);
        skill = skillRepository.save(skill);

        log.info("Updated connectors of skill '{}' id={} version={}: {}",
                skill.getName(), id, skill.getVersion(), skill.getConnectorCodes());
        return response(skill);
    }

    @Transactional
    public void delete(UUID id, UUID userId, boolean admin) {
        Skill skill = findOwnedOrSystemAdmin(id, userId, admin);
        if (isSystem(skill)) {
            // A system skill is a shared resource: a hard delete would orphan other people's agents and presets.
            // To «retire» one, an admin clears isPublic (it stops being offered to new agents).
            if (agentSkillRepository.existsBySkillId(skill.getId())) {
                throw new ConflictStatusException(
                        "System skill is bound to agents; unpublish it (isPublic=false) instead of deleting");
            }
            if (agentPresetRepository.existsBySkillNameReferenced(skill.getName(), skill.getLanguage())) {
                throw new ConflictStatusException(
                        "System skill is referenced by an agent preset; remove it from the preset first");
            }
        }
        skillRepository.softDelete(skill.getId(), LocalDateTime.now());
        // The bindings (other people's included — the skill may have been installed while public) are deleted
        // right away: a skill grants no access of its own, so there is nothing per-agent to recompute.
        int unbound = agentSkillRepository.deleteBySkillId(skill.getId());
        skillPolicySync.removeEverywhere(skill.getId());
        log.info("Soft-deleted skill '{}' id={} by user={}, unbound from {} agent(s)",
                skill.getName(), id, userId, unbound);
    }

    /**
     * Create the skill an app brought at link time: the row is the user's copy, the app keeps the
     * declaration in {@code apps.skill}. One skill per instance — a live or soft-deleted origin blocks
     * creation, because deleting the skill was the user's decision.
     *
     * <p>The name, connectors, category and visibility are the backend's, not the app's: otherwise an
     * app could claim another connector or publish a skill on the user's behalf. The name counter goes
     * through hyphens ({@code app-code-2}) — a skill name is kebab-case.
     *
     * @return the created skill, or empty when the instance already owns one (including a race lost
     *         on the unique origin index)
     */
    @Transactional
    public Optional<Skill> createFromApp(UUID userId, UUID originConnectionId, String appCode,
                                         String appTitle, AppSkill declaration) {
        if (skillRepository.findByOriginConnectionId(originConnectionId).isPresent()) {
            return Optional.empty();
        }

        Skill skill = Skill.builder()
                .name(availableName(userId, "app-" + appCode))
                .title(skillTitle(declaration, appTitle, appCode))
                .description(declaration.description())
                .mdContent(declaration.body())
                .connectors(List.of(ConnectorRequirement.of(APP_CONNECTOR_CODE)))
                .disclosure(Disclosure.LAZY)
                .category(ContentCategory.PLATFORM)
                .starters(new ArrayList<>(declaration.starters() != null ? declaration.starters() : List.of()))
                .userId(userId)
                .isPublic(false)
                .originConnectionId(originConnectionId)
                .updatedBy(SkillUpdatedBy.APP)
                .build();

        // The link path locks the app row (AppRepository.findByIdForUpdate) for the whole call, and it
        // is the only caller: the check above and this insert are serialised, so there is no unique-
        // origin race left to catch. Catching it here would be worse than useless — the exception from
        // the repository proxy marks the caller's transaction rollback-only before the catch runs.
        skill = skillRepository.save(skill);

        log.info("Created app skill '{}' id={} for connection {}", skill.getName(), skill.getId(), originConnectionId);
        return Optional.of(skill);
    }

    /**
     * Refresh an app-brought skill from the app's latest declaration — until the user's first edit.
     * A user edit of any kind (the body or only the rules) flips {@link SkillUpdatedBy#USER}, and from
     * then on the declaration is stored but never copied over the user's text.
     */
    @Transactional
    public void applyAppDeclaration(Skill skill, String appCode, String appTitle, AppSkill declaration) {
        // Re-read under the row lock: a user edit committed between the link's read and this refresh
        // must win. Without the lock the link would overwrite the text it never saw, leaving
        // updated_by=USER over the app's text — the one loss the whole flag exists to prevent.
        Skill current = skillRepository.findByIdForUpdate(skill.getId()).orElse(null);
        if (current == null || current.getOriginConnectionId() == null
                || current.getUpdatedBy() != SkillUpdatedBy.APP) {
            return;
        }
        current.setTitle(skillTitle(declaration, appTitle, appCode));
        current.setDescription(declaration.description());
        current.setMdContent(declaration.body());
        current.setStarters(new ArrayList<>(declaration.starters() != null ? declaration.starters() : List.of()));
        current.setVersion(current.getVersion() + 1);
        skillRepository.save(current);
        log.info("Refreshed app skill '{}' id={} version={}", current.getName(), current.getId(), current.getVersion());
    }

    /** The live skill an instance owns, if any — what the app may still refresh at link time. */
    public Optional<Skill> findLiveOriginSkill(UUID originConnectionId) {
        return skillRepository.findByOriginConnectionIdAndDeletedAtIsNull(originConnectionId);
    }

    /**
     * Delete the skill an instance owns, if it is still live. An already deleted one is skipped: the
     * app deletion must not fail on it, and {@link #delete} answers 404 for a soft-deleted row.
     */
    @Transactional
    public void deleteOriginSkill(UUID originConnectionId, UUID userId) {
        skillRepository.findByOriginConnectionIdAndDeletedAtIsNull(originConnectionId)
                .ifPresent(skill -> delete(skill.getId(), userId, false));
    }

    private String availableName(UUID userId, String base) {
        String candidate = base;
        int suffix = 2;
        while (skillRepository.existsByKeyNotDeleted(userId, candidate, null)) {
            candidate = base + "-" + suffix++;
        }
        return candidate;
    }

    private static String skillTitle(AppSkill declaration, String appTitle, String appCode) {
        if (declaration.title() != null && !declaration.title().isBlank()) {
            return declaration.title();
        }
        if (appTitle != null && !appTitle.isBlank()) {
            return appTitle;
        }
        return appCode;
    }

    /**
     * An app skill is frozen where the app's identity is baked in: the name follows the code, the
     * requirement is the {@code app} connector, and publishing is refused because nobody else has this
     * origin instance.
     */
    private static void validateOriginEdit(Skill skill, String name, List<ConnectorRequirement> connectors,
                                           Boolean isPublic) {
        if (skill.getOriginConnectionId() == null) {
            return;
        }
        if (!skill.getName().equals(name)) {
            throw new BadRequestStatusException(
                    "An app skill cannot be renamed: its name follows the app code");
        }
        validateOriginConnectors(skill, connectors);
        if (Boolean.TRUE.equals(isPublic)) {
            throw new BadRequestStatusException(
                    "An app skill cannot be published: the instance it needs belongs to its owner");
        }
    }

    /**
     * The freeze lives on both edit paths — {@link #update} and {@link #updateConnectors} — otherwise
     * the narrower endpoint would be a way around it.
     */
    private static void validateOriginConnectors(Skill skill, List<ConnectorRequirement> connectors) {
        if (skill.getOriginConnectionId() == null) {
            return;
        }
        List<String> codes = ConnectorRequirement.codes(connectors);
        if (codes.size() != 1 || !APP_CONNECTOR_CODE.equals(codes.get(0))) {
            throw new BadRequestStatusException(
                    "An app skill must keep exactly the '" + APP_CONNECTOR_CODE + "' connector, got: " + codes);
        }
    }

    private SkillResponse response(Skill skill) {
        return SkillResponse.from(skill, originOf(skill));
    }

    private SkillOriginResponse originOf(Skill skill) {
        if (skill.getOriginConnectionId() == null) {
            return null;
        }
        return origins(List.of(skill.getOriginConnectionId())).get(skill.getOriginConnectionId());
    }

    /** The listing's origin blocks in two queries for the whole page, not two per skill. */
    private Map<UUID, SkillOriginResponse> originsOf(Collection<Skill> skills) {
        List<UUID> ids = skills.stream()
                .map(Skill::getOriginConnectionId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        return origins(ids);
    }

    private Map<UUID, SkillOriginResponse> origins(Collection<UUID> connectionIds) {
        if (connectionIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Connection> connections = connectionRepository.findByIdInNotDeleted(connectionIds).stream()
                .collect(Collectors.toMap(Connection::getId, connection -> connection));
        Map<UUID, App> apps = appRepository.findAllByIdInNotDeleted(connectionIds).stream()
                .collect(Collectors.toMap(App::getId, app -> app));

        Map<UUID, SkillOriginResponse> origins = new HashMap<>();
        for (UUID connectionId : connectionIds) {
            App app = apps.get(connectionId);
            if (app == null && !connections.containsKey(connectionId)) {
                continue;
            }
            origins.put(connectionId, new SkillOriginResponse(
                    connectionId,
                    app != null ? app.getCode() : null,
                    app != null ? app.getTitle() : null,
                    app != null ? app.getVersion() : null,
                    deviceName(app != null ? app.getInfo() : null)));
        }
        return origins;
    }

    private static String deviceName(Map<String, Object> info) {
        Object name = info != null ? info.get("deviceName") : null;
        return name != null ? name.toString() : null;
    }

    public Skill findOwnedSkill(UUID id, UUID userId) {
        Skill skill = skillRepository.findByIdNotDeleted(id)
                .orElseThrow(() -> new NotFoundStatusException("Skill not found"));
        if (!skill.getUserId().equals(userId)) {
            throw new ForbiddenStatusException("Access denied");
        }
        return skill;
    }

    /**
     * One's own record — as in {@link #findOwnedSkill}; an ADMIN additionally edits system skills
     * (owned by {@link SystemSkillBootstrap#SYSTEM_USER_ID}). Other users' records are out of reach even
     * for an admin — they manage platform assets, not other people's skills.
     */
    public Skill findOwnedOrSystemAdmin(UUID id, UUID userId, boolean admin) {
        Skill skill = skillRepository.findByIdNotDeleted(id)
                .orElseThrow(() -> new NotFoundStatusException("Skill not found"));
        if (skill.getUserId().equals(userId)) {
            return skill;
        }
        if (admin && isSystem(skill)) {
            return skill;
        }
        throw new ForbiddenStatusException("Access denied");
    }

    private static boolean isSystem(Skill skill) {
        return isSystemOwner(skill.getUserId());
    }

    private static boolean isSystemOwner(UUID ownerId) {
        return SystemSkillBootstrap.SYSTEM_USER_ID.equals(ownerId);
    }

    public Skill findAccessibleSkill(UUID id, UUID userId) {
        Skill skill = skillRepository.findByIdNotDeleted(id)
                .orElseThrow(() -> new NotFoundStatusException("Skill not found"));
        if (!skill.getUserId().equals(userId) && !skill.getIsPublic()) {
            throw new ForbiddenStatusException("Access denied");
        }
        return skill;
    }

    private Page<SkillResponse> findSkills(Specification<Skill> filter, String search, String connectorCode,
                                           ContentCategory category, ContentTag tag, int page, int size) {
        PageRequest pageRequest = PageRequests.of(page, size, Sort.by("createdAt").descending());
        Specification<Skill> spec = SkillSpecs.notDeleted().and(filter);
        if (search != null && !search.isBlank()) {
            spec = spec.and(SkillSpecs.searchByNameOrDescription(search));
        }
        if (connectorCode != null && !connectorCode.isBlank()) {
            spec = spec.and(SkillSpecs.hasConnector(connectorCode));
        }
        if (category != null) {
            spec = spec.and(SkillSpecs.hasCategory(category));
        }
        if (tag != null) {
            spec = spec.and(SkillSpecs.hasTag(tag.name()));
        }
        Page<Skill> skills = skillRepository.findAll(spec, pageRequest);
        Map<UUID, SkillOriginResponse> origins = originsOf(skills.getContent());
        // The null guard is not cosmetic: `origins` is `Map.of()` when the page has no app skill, and
        // `Map.of().get(null)` throws — every ordinary listing would answer 500.
        return skills.map(skill -> SkillResponse.from(skill,
                skill.getOriginConnectionId() == null
                        ? null
                        : origins.get(skill.getOriginConnectionId())));
    }

    private void validateName(String name) {
        if (!NAME_SLUG.matcher(name).matches()) {
            throw new BadRequestStatusException("Skill name must be a kebab-case code "
                    + "(lowercase letters, digits, hyphens), got: '" + name + "'");
        }
    }

    /**
     * What a declaration cannot check about itself. Codes must exist in the connector catalogue (the
     * {@code connectors} table) — the same source of truth binding uses
     * ({@link ru.agimate.controlapi.service.connection.ConnectionBindingService}). The catalogue is
     * wider than the SPI registry: besides the code handlers it holds static connectors with no handler
     * ({@code app}, {@code claude-code}) — a skill may declare those too (an INSTANCE connector is bound
     * later, by hand, using a connectionId). {@code params} pre-fill an integration's credentials form,
     * so they are accepted only for a connector that has one, only for its declared fields, and never
     * for a SECRET field: a skill is readable by everyone it is offered to. Public because the system
     * skill seeder goes through it too — a seed file is not trusted more than an upload.
     *
     * @return the requirements with {@code params} normalised by field type, as a connection's are on
     *         write — store these, not the input, or the two sides stop meeting as equal strings
     */
    public List<ConnectorRequirement> validateConnectors(List<ConnectorRequirement> requirements) {
        if (requirements.isEmpty()) {
            return requirements;
        }
        List<String> unknown = ConnectorRequirement.codes(requirements).stream()
                .filter(code -> !connectorRepository.existsById(code))
                .toList();
        if (!unknown.isEmpty()) {
            throw new BadRequestStatusException("Unknown connector code(s): " + String.join(", ", unknown));
        }
        List<ConnectorRequirement> normalized = new ArrayList<>();
        for (ConnectorRequirement requirement : requirements) {
            if (requirement.params() == null) {
                normalized.add(requirement);
                continue;
            }
            Map<String, CredentialField> fields = connectorRegistry.findIntegrationHandler(requirement.code())
                    .map(IntegrationConnectorHandler::getCredentialFields)
                    .orElseThrow(() -> new BadRequestStatusException("Requirement '" + requirement.key()
                            + "': connector " + requirement.code() + " has no credentials form, params are not applicable"));
            for (String field : requirement.params().keySet()) {
                CredentialField declared = fields.get(field);
                if (declared == null) {
                    throw new BadRequestStatusException("Requirement '" + requirement.key() + "': connector "
                            + requirement.code() + " has no field '" + field + "'");
                }
                if (declared.secret()) {
                    throw new BadRequestStatusException("Requirement '" + requirement.key() + "': field '" + field
                            + "' is a secret and cannot be declared in a skill");
                }
            }
            Map<String, String> params = CredentialField.normalize(fields, requirement.params());
            params.forEach((field, value) -> {
                if (!fields.get(field).opens(value)) {
                    throw new BadRequestStatusException("Requirement '" + requirement.key() + "': field '" + field
                            + "' carries credentials (userinfo or a query) and cannot be declared in a skill");
                }
            });
            normalized.add(requirement.withParams(params));
        }
        return normalized;
    }
}
