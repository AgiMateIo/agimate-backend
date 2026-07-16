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

    private final SkillRepository skillRepository;
    private final AgentRepository agentRepository;
    private final AgentSkillRepository agentSkillRepository;
    private final AgentPresetRepository agentPresetRepository;
    private final ConnectorRepository connectorRepository;

    public Page<SkillResponse> getMySkills(UUID userId, String search, String connectorCode, int page, int size) {
        return findSkills(SkillSpecs.ownedBy(userId), search, connectorCode, page, size);
    }

    public Page<SkillResponse> getPublicSkills(String search, String connectorCode, int page, int size) {
        return findSkills(SkillSpecs.isPublic(), search, connectorCode, page, size);
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
        return doCreate(userId, request.resolveIsPublic(), request);
    }

    /**
     * Создать системный скилл (owner — {@link SystemSkillBootstrap#SYSTEM_USER_ID}, всегда public,
     * чтобы его можно было привязывать к чужим агентам). ADMIN-only на уровне контроллера.
     */
    @Transactional
    public SkillResponse createSystem(CreateSkillRequest request) {
        return doCreate(SystemSkillBootstrap.SYSTEM_USER_ID, true, request);
    }

    private SkillResponse doCreate(UUID ownerId, boolean isPublic, CreateSkillRequest request) {
        SkillFrontmatterParser.ParsedSkill parsed = SkillFrontmatterParser.parse(request.skillMd());
        validateConnectorCodes(parsed.connectors());

        if (skillRepository.existsByUserIdAndNameNotDeleted(ownerId, parsed.name())) {
            throw new ConflictStatusException("Skill with name '" + parsed.name() + "' already exists");
        }

        Skill skill = Skill.builder()
                .name(parsed.name())
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
        return SkillResponse.from(skill);
    }

    @Transactional
    public SkillResponse update(UUID id, UUID userId, boolean admin, UpdateSkillRequest request) {
        Skill skill = findOwnedOrSystemAdmin(id, userId, admin);
        boolean system = isSystem(skill);

        SkillFrontmatterParser.ParsedSkill parsed = SkillFrontmatterParser.parse(request.skillMd());
        validateConnectorCodes(parsed.connectors());

        if (!skill.getName().equals(parsed.name())) {
            if (system) {
                // Имя системного скилла — ключ ссылок: (SYSTEM_USER_ID, name) в сидере и в
                // preset.skill_names. Переименование осиротило бы эти ссылки — запрещаем.
                throw new BadRequestStatusException("System skill cannot be renamed");
            }
            if (skillRepository.existsByUserIdAndNameNotDeleted(skill.getUserId(), parsed.name())) {
                throw new ConflictStatusException("Skill with name '" + parsed.name() + "' already exists");
            }
        }

        skill.setName(parsed.name());
        skill.setDescription(parsed.description());
        skill.setMdContent(parsed.body());
        skill.setConnectorCodes(new ArrayList<>(parsed.connectors()));
        skill.setIsPublic(request.resolveIsPublic());
        skill.setVersion(skill.getVersion() + 1);

        try {
            skill = skillRepository.save(skill);
        } catch (DataIntegrityViolationException e) {
            throw new ConflictStatusException("Skill with name '" + parsed.name() + "' already exists");
        }

        log.info("Updated skill '{}' id={} version={}", skill.getName(), id, skill.getVersion());
        return SkillResponse.from(skill);
    }

    /**
     * Точечно заменить список коннекторов скилла (без правки тела/имени). Права — как у полного
     * {@link #update}: свой скилл или системный для ADMIN. Add-only-политика ({@code AgentSkillPolicyService})
     * означает, что уже привязанные агенты новые коннекторы сами не подхватят — их синхронизируют явно
     * (per-agent {@code sync-policies}); поэтому здесь только бампаем версию для детекции дрейфа.
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
            // Системный скилл — общий ресурс: hard-delete осиротил бы чужих агентов/пресеты.
            // Для «вывода из оборота» админ снимает isPublic (перестаёт предлагаться новым).
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
        // Привязки (включая чужие — скилл могли ставить как публичный) удаляем сразу: политики
        // add-only (AgentSkillPolicyService), так что пересчёт по агентам не требуется.
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
     * Своя запись — как {@link #findOwnedSkill}; ADMIN дополнительно правит системные скилы
     * (owner — {@link SystemSkillBootstrap#SYSTEM_USER_ID}). Чужие пользовательские записи и для
     * админа недоступны — он управляет платформенными ассетами, а не чужими скилами.
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
     * Коды коннекторов скилла должны существовать в каталоге коннекторов (таблица {@code connectors}) —
     * это же источник истины для привязки ({@link ru.agimate.controlapi.service.connection.ConnectionBindingService}).
     * Каталог шире SPI-реестра: помимо код-хендлеров в нём есть статические коннекторы без хендлера
     * ({@code app}, {@code claude-code}) — их скилл тоже может объявлять (INSTANCE-коннектор привязывается
     * позже вручную по connectionId). Пустой список (скилл без коннекторов) допустим.
     */
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
