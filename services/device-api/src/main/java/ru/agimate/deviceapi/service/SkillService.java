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

    public SkillDetailResponse getSkillDetail(UUID pubId, UUID userPubId) {
        Skill skill = findAccessibleSkill(pubId, userPubId);
        String skillMd = skillFileService.readSkillMd(resolveFileOwnerPubId(skill));
        return SkillDetailResponse.from(skill, skillMd);
    }

    public Page<AgentSummaryResponse> getSkillAgents(UUID pubId, UUID userPubId, String search, int page, int size) {
        findAccessibleSkill(pubId, userPubId);
        String normalizedSearch = (search == null || search.isBlank()) ? null : search.trim();
        PageRequest pageRequest = PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE), Sort.by("name").ascending());
        return agentRepository.findBySkillPubId(pubId, userPubId, normalizedSearch, pageRequest)
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

        skillFileService.saveSkillMd(skill.getPubId(), request.skillMd());

        log.info("Created skill '{}' pubId={} for user={}", skill.getName(), skill.getPubId(), userPubId);
        return SkillResponse.from(skill);
    }

    @Transactional
    public SkillResponse update(UUID pubId, UUID userPubId, UpdateSkillRequest request) {
        Skill skill = findOwnedSkill(pubId, userPubId);
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

        skillFileService.saveSkillMd(skill.getPubId(), request.skillMd());

        log.info("Updated skill '{}' pubId={} version={}", skill.getName(), pubId, skill.getVersion());
        return SkillResponse.from(skill);
    }

    @Transactional
    public void delete(UUID pubId, UUID userPubId) {
        Skill skill = findOwnedSkill(pubId, userPubId);
        requireNotFeaturedClone(skill);
        skillRepository.softDelete(skill.getId(), LocalDateTime.now());
        skillFileService.deleteAll(skill.getPubId());
        log.info("Soft-deleted skill '{}' pubId={}", skill.getName(), pubId);
    }

    @Transactional
    public SkillResponse clone(UUID pubId, UUID userPubId) {
        Skill source = findAccessibleSkill(pubId, userPubId);

        if (source.getUserPubId().equals(userPubId)) {
            throw new BadRequestStatusException("Cannot clone your own skill");
        }

        skillRepository.findByUserPubIdAndNameNotDeleted(userPubId, source.getName())
                .ifPresent(existing -> {
                    throw new SkillConflictException(
                            "Skill with name '" + source.getName() + "' already exists in your collection",
                            existing.getPubId()
                    );
                });

        Skill clone = Skill.builder()
                .name(source.getName())
                .description(source.getDescription())
                .userPubId(userPubId)
                .parentPubId(source.getPubId())
                .isPublic(false)
                .build();

        try {
            clone = skillRepository.save(clone);
        } catch (DataIntegrityViolationException e) {
            throw new ConflictStatusException("Skill with name '" + source.getName() + "' already exists in your collection");
        }

        if (!source.getIsFeatured()) {
            skillFileService.copyAll(source.getPubId(), clone.getPubId());
        }
        skillConnectorService.cloneBindings(source, clone);

        log.info("Cloned skill '{}' from pubId={} to pubId={} for user={}", source.getName(), source.getPubId(), clone.getPubId(), userPubId);
        return SkillResponse.from(clone);
    }

    @Transactional
    public void touchUpdatedAt(UUID pubId, UUID userPubId) {
        Skill skill = findOwnedSkill(pubId, userPubId);
        skillRepository.touchUpdatedAt(skill.getId(), LocalDateTime.now());
    }

    public Skill findOwnedSkill(UUID pubId, UUID userPubId) {
        Skill skill = skillRepository.findByPubIdNotDeleted(pubId)
                .orElseThrow(() -> new NotFoundStatusException("Skill not found"));
        if (!skill.getUserPubId().equals(userPubId)) {
            throw new ForbiddenStatusException("Access denied");
        }
        return skill;
    }

    public Skill findAccessibleSkill(UUID pubId, UUID userPubId) {
        Skill skill = skillRepository.findByPubIdNotDeleted(pubId)
                .orElseThrow(() -> new NotFoundStatusException("Skill not found"));
        if (!skill.getUserPubId().equals(userPubId) && !skill.getIsPublic()) {
            throw new ForbiddenStatusException("Access denied");
        }
        return skill;
    }

    public UUID resolveFileOwnerPubId(Skill skill) {
        if (isFeaturedClone(skill)) {
            return skill.getParentPubId();
        }
        return skill.getPubId();
    }

    public void requireNotFeaturedClone(Skill skill) {
        if (isFeaturedClone(skill)) {
            throw new ForbiddenStatusException("Cannot edit a featured skill clone");
        }
    }

    private boolean isFeaturedClone(Skill skill) {
        return skill.getParentPubId() != null
                && skillRepository.existsByPubIdAndIsFeaturedTrue(skill.getParentPubId());
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
        Set<UUID> pubIds = skills.getContent().stream().map(Skill::getPubId).collect(Collectors.toSet());
        Map<UUID, UUID> myCopyMap = buildMyCopyMap(pubIds, userPubId);
        return skills.map(skill -> SkillResponse.from(skill, myCopyMap.get(skill.getPubId())));
    }

    private Map<UUID, UUID> buildMyCopyMap(Set<UUID> parentPubIds, UUID userPubId) {
        if (parentPubIds.isEmpty()) {
            return Map.of();
        }
        return skillRepository.findMyClonesByParentPubIds(parentPubIds, userPubId)
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
