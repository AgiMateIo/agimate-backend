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
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.ConflictStatusException;
import ru.agimate.common.rest.error.ForbiddenStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.controlapi.controller.manage.dto.AgentSummaryResponse;
import ru.agimate.controlapi.controller.manage.dto.CreateSkillRequest;
import ru.agimate.controlapi.controller.manage.dto.SkillDetailResponse;
import ru.agimate.controlapi.controller.manage.dto.SkillListScope;
import ru.agimate.controlapi.controller.manage.dto.SkillResponse;
import ru.agimate.controlapi.controller.manage.dto.UpdateSkillConnectorsRequest;
import ru.agimate.controlapi.controller.manage.dto.UpdateSkillRequest;
import ru.agimate.controlapi.database.entities.Skill;
import ru.agimate.controlapi.database.repositories.AgentPresetRepository;
import ru.agimate.controlapi.database.repositories.AgentRepository;
import ru.agimate.controlapi.database.repositories.AgentSkillRepository;
import ru.agimate.controlapi.database.repositories.ConnectorRepository;
import ru.agimate.controlapi.database.repositories.SkillRepository;
import ru.agimate.controlapi.database.repositories.SkillSpecs;
import ru.agimate.controlapi.util.SkillFrontmatterParser;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SkillService {

    private static final int MAX_PAGE_SIZE = 100;

    /** A skill's {@code name} is a machine code: lower-case latin letters and digits joined by hyphens (kebab-case). */
    private static final java.util.regex.Pattern NAME_SLUG =
            java.util.regex.Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");

    private final SkillRepository skillRepository;
    private final AgentRepository agentRepository;
    private final AgentSkillRepository agentSkillRepository;
    private final AgentPresetRepository agentPresetRepository;
    private final ConnectorRepository connectorRepository;

    public Page<SkillResponse> getSkills(UUID userId, SkillListScope scope, String search, String connectorCode,
                                         int page, int size) {
        Specification<Skill> base = scope == SkillListScope.PUBLIC
                ? SkillSpecs.isPublic()
                : SkillSpecs.ownedBy(userId);
        return findSkills(base, search, connectorCode, page, size);
    }

    public SkillDetailResponse getSkillDetail(UUID id, UUID userId) {
        Skill skill = findAccessibleSkill(id, userId);
        return SkillDetailResponse.from(skill);
    }

    public Page<AgentSummaryResponse> getSkillAgents(UUID id, UUID userId, String search, int page, int size) {
        findAccessibleSkill(id, userId);
        String normalizedSearch = (search == null || search.isBlank()) ? null : search.trim();
        PageRequest pageRequest = PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE), Sort.by("name").ascending());
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
        validateConnectorCodes(parsed.connectors());

        if (skillRepository.existsByUserIdAndNameNotDeleted(ownerId, parsed.name())) {
            throw new ConflictStatusException("Skill with name '" + parsed.name() + "' already exists");
        }

        Skill skill = Skill.builder()
                .name(parsed.name())
                .title(parsed.title())
                .description(parsed.description())
                .mdContent(parsed.body())
                .connectorCodes(new ArrayList<>(parsed.connectors()))
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
        return SkillResponse.from(update(id, userId, admin, request.skillMd(), request.isPublic()));
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
        validateConnectorCodes(parsed.connectors());

        if (!skill.getName().equals(parsed.name())) {
            if (system) {
                // A system skill's name is the key of every reference to it: (SYSTEM_USER_ID, name) in the seeder
                // and in preset.skill_names. Renaming would orphan those references — so we forbid it.
                throw new BadRequestStatusException("System skill cannot be renamed");
            }
            if (skillRepository.existsByUserIdAndNameNotDeleted(skill.getUserId(), parsed.name())) {
                throw new ConflictStatusException("Skill with name '" + parsed.name() + "' already exists");
            }
        }

        skill.setName(parsed.name());
        skill.setTitle(parsed.title());
        skill.setDescription(parsed.description());
        skill.setMdContent(parsed.body());
        skill.setConnectorCodes(new ArrayList<>(parsed.connectors()));
        if (isPublic != null) {
            skill.setIsPublic(isPublic);
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
     * Replace a skill's connector list in place (without touching the body or the name). The rights are
     * the same as for a full {@link #update}: one's own skill, or a system one for ADMIN. A skill only
     * declares what it needs and never binds anything, so a new connector reaches no already-bound
     * agent by itself — the version bump is what surfaces the drift, against
     * {@code AgentSkill.installedSkillVersion}.
     */
    @Transactional
    public SkillResponse updateConnectors(UUID id, UUID userId, boolean admin, UpdateSkillConnectorsRequest request) {
        Skill skill = findOwnedOrSystemAdmin(id, userId, admin);
        validateConnectorCodes(request.connectorCodes());

        skill.setConnectorCodes(new ArrayList<>(request.connectorCodes()));
        skill.setVersion(skill.getVersion() + 1);
        skill = skillRepository.save(skill);

        log.info("Updated connectors of skill '{}' id={} version={}: {}",
                skill.getName(), id, skill.getVersion(), skill.getConnectorCodes());
        return SkillResponse.from(skill);
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
            if (agentPresetRepository.existsBySkillNameReferenced(skill.getName())) {
                throw new ConflictStatusException(
                        "System skill is referenced by an agent preset; remove it from the preset first");
            }
        }
        skillRepository.softDelete(skill.getId(), LocalDateTime.now());
        // The bindings (other people's included — the skill may have been installed while public) are deleted
        // right away: a skill grants no access of its own, so there is nothing per-agent to recompute.
        int unbound = agentSkillRepository.deleteBySkillId(skill.getId());
        log.info("Soft-deleted skill '{}' id={} by user={}, unbound from {} agent(s)",
                skill.getName(), id, userId, unbound);
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
        return SystemSkillBootstrap.SYSTEM_USER_ID.equals(skill.getUserId());
    }

    public Skill findAccessibleSkill(UUID id, UUID userId) {
        Skill skill = skillRepository.findByIdNotDeleted(id)
                .orElseThrow(() -> new NotFoundStatusException("Skill not found"));
        if (!skill.getUserId().equals(userId) && !skill.getIsPublic()) {
            throw new ForbiddenStatusException("Access denied");
        }
        return skill;
    }

    private Page<SkillResponse> findSkills(Specification<Skill> filter, String search, String connectorCode, int page, int size) {
        PageRequest pageRequest = PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE), Sort.by("createdAt").descending());
        Specification<Skill> spec = SkillSpecs.notDeleted().and(filter);
        if (search != null && !search.isBlank()) {
            spec = spec.and(SkillSpecs.searchByNameOrDescription(search));
        }
        if (connectorCode != null && !connectorCode.isBlank()) {
            spec = spec.and(SkillSpecs.hasConnector(connectorCode));
        }
        return skillRepository.findAll(spec, pageRequest).map(SkillResponse::from);
    }

    /**
     * A skill's connector codes must exist in the connector catalogue (the {@code connectors} table) —
     * that is the same source of truth binding uses
     * ({@link ru.agimate.controlapi.service.connection.ConnectionBindingService}). The catalogue is
     * wider than the SPI registry: besides the code handlers it holds static connectors with no handler
     * ({@code app}, {@code claude-code}) — a skill may declare those too (an INSTANCE connector is bound
     * later, by hand, using a connectionId). An empty list (a skill with no connectors) is acceptable.
     */
    private void validateName(String name) {
        if (!NAME_SLUG.matcher(name).matches()) {
            throw new BadRequestStatusException("Skill name must be a kebab-case code "
                    + "(lowercase letters, digits, hyphens), got: '" + name + "'");
        }
    }

    private void validateConnectorCodes(List<String> codes) {
        if (codes.isEmpty()) {
            return;
        }
        List<String> unknown = codes.stream()
                .distinct()
                .filter(code -> !connectorRepository.existsById(code))
                .toList();
        if (!unknown.isEmpty()) {
            throw new BadRequestStatusException("Unknown connector code(s): " + String.join(", ", unknown));
        }
    }
}
