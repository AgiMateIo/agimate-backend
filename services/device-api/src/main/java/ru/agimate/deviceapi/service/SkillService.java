package ru.agimate.deviceapi.service;

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
import ru.agimate.deviceapi.controller.error.SkillConflictException;
import ru.agimate.deviceapi.controller.manage.dto.*;
import ru.agimate.deviceapi.database.entities.Skill;
import ru.agimate.deviceapi.database.repositories.AgentRepository;
import ru.agimate.deviceapi.database.repositories.SkillRepository;
import ru.agimate.deviceapi.database.repositories.SkillSpecs;
import ru.agimate.deviceapi.util.SkillFrontmatterParser;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SkillService {

    private static final int MAX_PAGE_SIZE = 100;

    private final SkillRepository skillRepository;
    private final SkillFileService skillFileService;
    private final SkillConnectorService skillConnectorService;
    private final AgentRepository agentRepository;

    public Page<SkillResponse> getMySkills(UUID userPubId, String search, String connectorCode, int page, int size) {
        return findSkills(SkillSpecs.ownedBy(userPubId), search, connectorCode, page, size);
    }

    public Page<SkillResponse> getPublicSkills(UUID userPubId, String search, String connectorCode, int page, int size) {
        return findSkillsWithMyCopy(SkillSpecs.publicNotFeatured(), userPubId, search, connectorCode, page, size);
    }

    public Page<SkillResponse> getFeaturedSkills(UUID userPubId, String search, String connectorCode, int page, int size) {
        return findSkillsWithMyCopy(SkillSpecs.featured(), userPubId, search, connectorCode, page, size);
    }

    public SkillDetailResponse getSkillDetail(UUID id, UUID userPubId) {
        Skill skill = findAccessibleSkill(id, userPubId);
        String skillMd = skillFileService.readSkillMd(resolveFileOwnerId(skill));
        return SkillDetailResponse.from(skill, skillMd);
    }

    public Page<AgentSummaryResponse> getSkillAgents(UUID id, UUID userPubId, String search, int page, int size) {
        findAccessibleSkill(id, userPubId);
        String normalizedSearch = (search == null || search.isBlank()) ? null : search.trim();
        PageRequest pageRequest = PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE), Sort.by("name").ascending());
        return agentRepository.findBySkillId(id, userPubId, normalizedSearch, pageRequest)
                .map(AgentSummaryResponse::from);
    }

    @Transactional
    public SkillResponse create(UUID userPubId, CreateSkillRequest request) {
        SkillFrontmatterParser.Frontmatter frontmatter = SkillFrontmatterParser.parse(request.skillMd());

        if (skillRepository.existsByUserPubIdAndNameNotDeleted(userPubId, frontmatter.name())) {
            throw new ConflictStatusException("Skill with name '" + frontmatter.name() + "' already exists");
        }

        Skill skill = Skill.builder()
                .name(frontmatter.name())
                .description(frontmatter.description())
                .userPubId(userPubId)
                .isPublic(request.resolveIsPublic())
                .build();

        try {
            skill = skillRepository.save(skill);
        } catch (DataIntegrityViolationException e) {
            throw new ConflictStatusException("Skill with name '" + frontmatter.name() + "' already exists");
        }

        skillFileService.saveSkillMd(skill.getId(), request.skillMd());

        log.info("Created skill '{}' id={} for user={}", skill.getName(), skill.getId(), userPubId);
        return SkillResponse.from(skill);
    }

    @Transactional
    public SkillResponse update(UUID id, UUID userPubId, UpdateSkillRequest request) {
        Skill skill = findOwnedSkill(id, userPubId);
        requireNotFeaturedClone(skill);

        SkillFrontmatterParser.Frontmatter frontmatter = SkillFrontmatterParser.parse(request.skillMd());

        if (!skill.getName().equals(frontmatter.name())
                && skillRepository.existsByUserPubIdAndNameNotDeleted(userPubId, frontmatter.name())) {
            throw new ConflictStatusException("Skill with name '" + frontmatter.name() + "' already exists");
        }

        skill.setName(frontmatter.name());
        skill.setDescription(frontmatter.description());
        skill.setIsPublic(request.resolveIsPublic());
        skill.setVersion(skill.getVersion() + 1);

        try {
            skill = skillRepository.save(skill);
        } catch (DataIntegrityViolationException e) {
            throw new ConflictStatusException("Skill with name '" + frontmatter.name() + "' already exists");
        }

        skillFileService.saveSkillMd(skill.getId(), request.skillMd());

        log.info("Updated skill '{}' id={} version={}", skill.getName(), id, skill.getVersion());
        return SkillResponse.from(skill);
    }

    @Transactional
    public void delete(UUID id, UUID userPubId) {
        Skill skill = findOwnedSkill(id, userPubId);
        requireNotFeaturedClone(skill);
        skillRepository.softDelete(skill.getId(), LocalDateTime.now());
        skillFileService.deleteAll(skill.getId());
        log.info("Soft-deleted skill '{}' id={}", skill.getName(), id);
    }

    @Transactional
    public SkillResponse clone(UUID id, UUID userPubId) {
        Skill source = findAccessibleSkill(id, userPubId);

        if (source.getUserPubId().equals(userPubId)) {
            throw new BadRequestStatusException("Cannot clone your own skill");
        }

        skillRepository.findByUserPubIdAndNameNotDeleted(userPubId, source.getName())
                .ifPresent(existing -> {
                    throw new SkillConflictException(
                            "Skill with name '" + source.getName() + "' already exists in your collection",
                            existing.getId()
                    );
                });

        Skill clone = Skill.builder()
                .name(source.getName())
                .description(source.getDescription())
                .userPubId(userPubId)
                .parentId(source.getId())
                .isPublic(false)
                .build();

        try {
            clone = skillRepository.save(clone);
        } catch (DataIntegrityViolationException e) {
            throw new ConflictStatusException("Skill with name '" + source.getName() + "' already exists in your collection");
        }

        if (!source.getIsFeatured()) {
            skillFileService.copyAll(source.getId(), clone.getId());
        }
        skillConnectorService.cloneBindings(source, clone);

        log.info("Cloned skill '{}' from id={} to id={} for user={}", source.getName(), source.getId(), clone.getId(), userPubId);
        return SkillResponse.from(clone);
    }

    @Transactional
    public void touchUpdatedAt(UUID id, UUID userPubId) {
        Skill skill = findOwnedSkill(id, userPubId);
        skillRepository.touchUpdatedAt(skill.getId(), LocalDateTime.now());
    }

    public Skill findOwnedSkill(UUID id, UUID userPubId) {
        Skill skill = skillRepository.findByIdNotDeleted(id)
                .orElseThrow(() -> new NotFoundStatusException("Skill not found"));
        if (!skill.getUserPubId().equals(userPubId)) {
            throw new ForbiddenStatusException("Access denied");
        }
        return skill;
    }

    public Skill findAccessibleSkill(UUID id, UUID userPubId) {
        Skill skill = skillRepository.findByIdNotDeleted(id)
                .orElseThrow(() -> new NotFoundStatusException("Skill not found"));
        if (!skill.getUserPubId().equals(userPubId) && !skill.getIsPublic()) {
            throw new ForbiddenStatusException("Access denied");
        }
        return skill;
    }

    public UUID resolveFileOwnerId(Skill skill) {
        if (isFeaturedClone(skill)) {
            return skill.getParentId();
        }
        return skill.getId();
    }

    public void requireNotFeaturedClone(Skill skill) {
        if (isFeaturedClone(skill)) {
            throw new ForbiddenStatusException("Cannot edit a featured skill clone");
        }
    }

    private boolean isFeaturedClone(Skill skill) {
        return skill.getParentId() != null
                && skillRepository.existsByIdAndIsFeaturedTrue(skill.getParentId());
    }

    private Page<Skill> querySkills(Specification<Skill> filter, String search, String connectorCode, int page, int size) {
        PageRequest pageRequest = buildPageRequest(page, size);
        Specification<Skill> spec = SkillSpecs.notDeleted().and(filter);
        if (search != null && !search.isBlank()) {
            spec = spec.and(SkillSpecs.searchByNameOrDescription(search));
        }
        if (connectorCode != null && !connectorCode.isBlank()) {
            spec = spec.and(SkillSpecs.hasConnector(connectorCode));
        }
        return skillRepository.findAll(spec, pageRequest);
    }

    private Page<SkillResponse> findSkills(Specification<Skill> filter, String search, String connectorCode, int page, int size) {
        return querySkills(filter, search, connectorCode, page, size).map(SkillResponse::from);
    }

    private Page<SkillResponse> findSkillsWithMyCopy(Specification<Skill> filter, UUID userPubId, String search, String connectorCode, int page, int size) {
        Page<Skill> skills = querySkills(filter, search, connectorCode, page, size);
        Set<UUID> ids = skills.getContent().stream().map(Skill::getId).collect(Collectors.toSet());
        Map<UUID, UUID> myCopyMap = buildMyCopyMap(ids, userPubId);
        return skills.map(skill -> SkillResponse.from(skill, myCopyMap.get(skill.getId())));
    }

    private Map<UUID, UUID> buildMyCopyMap(Set<UUID> parentIds, UUID userPubId) {
        if (parentIds.isEmpty()) {
            return Map.of();
        }
        return skillRepository.findMyClonesByParentIds(parentIds, userPubId)
                .stream()
                .collect(Collectors.toMap(
                        row -> (UUID) row[0],
                        row -> (UUID) row[1],
                        (existing, replacement) -> existing
                ));
    }

    private PageRequest buildPageRequest(int page, int size) {
        return PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE), Sort.by("createdAt").descending());
    }
}
