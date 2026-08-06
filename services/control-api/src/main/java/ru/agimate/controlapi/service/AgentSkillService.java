package ru.agimate.controlapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.ConflictStatusException;
import ru.agimate.common.rest.error.ForbiddenStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.controlapi.controller.agent.dto.AgentSkillWithConnectorsResponse;
import ru.agimate.controlapi.controller.manage.dto.AgentSkillResponse;
import ru.agimate.controlapi.controller.manage.dto.SkillConnectorStatus;
import ru.agimate.controlapi.database.entities.AgentSkill;
import ru.agimate.controlapi.database.entities.AgentSkillConnection;
import ru.agimate.controlapi.database.entities.Connection;
import ru.agimate.controlapi.database.entities.Skill;
import ru.agimate.controlapi.database.repositories.AgentRepository;
import ru.agimate.controlapi.database.repositories.AgentSkillConnectionRepository;
import ru.agimate.controlapi.database.repositories.AgentSkillRepository;
import ru.agimate.controlapi.database.repositories.ConnectionRepository;
import ru.agimate.controlapi.database.repositories.SkillRepository;
import ru.agimate.controlapi.service.connection.ConnectionBindingService;
import ru.agimate.controlapi.service.connection.ConnectionBindingService.ConnectorKind;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AgentSkillService {

    private static final int MAX_PAGE_SIZE = 100;

    private final AgentSkillRepository agentSkillRepository;
    private final AgentRepository agentRepository;
    private final SkillRepository skillRepository;
    private final ConnectionRepository connectionRepository;
    private final AgentSkillConnectionRepository agentSkillConnectionRepository;
    private final ConnectionBindingService connectionBindingService;

    public Page<AgentSkillResponse> getAgentSkills(UUID agentId, UUID userId, int page, int size) {
        verifyAgentOwnership(agentId, userId);
        PageRequest pageRequest = PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE), Sort.by("createdAt").descending());
        Page<AgentSkill> agentSkills = agentSkillRepository.findByAgentId(agentId, pageRequest);

        var skillIds = agentSkills.getContent().stream()
                .map(AgentSkill::getSkillId)
                .collect(Collectors.toSet());

        Map<UUID, Skill> skillMap = skillIds.isEmpty()
                ? Map.of()
                : skillRepository.findByIdInNotDeleted(skillIds).stream()
                        .collect(Collectors.toMap(Skill::getId, s -> s));

        SkillResolution resolution = resolveSkills(agentId);

        return agentSkills.map(as -> {
            Skill skill = skillMap.get(as.getSkillId());
            String name = skill != null ? skill.getName() : null;
            boolean needsReinstall = skill != null
                    && (as.getInstalledSkillVersion() == null || skill.getVersion() > as.getInstalledSkillVersion());
            List<SkillConnectorStatus> connectors = skill == null ? List.of()
                    : statuses(resolution, as.getId(), skill.getConnectorCodes());
            return AgentSkillResponse.from(as, name, connectors, needsReinstall);
        });
    }

    public Page<AgentSkillWithConnectorsResponse> getAgentSkillsWithConnectors(UUID agentId, UUID userId, int page, int size) {
        verifyAgentOwnership(agentId, userId);
        PageRequest pageRequest = PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE), Sort.by("createdAt").descending());
        Page<UUID> skillIdsPage = agentSkillRepository.findSkillIdsByAgentId(agentId, pageRequest);

        Map<UUID, AgentSkillWithConnectorsResponse> resolved = resolveSkillsById(skillIdsPage.getContent());

        return skillIdsPage.map(id -> resolved.getOrDefault(id,
                new AgentSkillWithConnectorsResponse(id, null, null, List.of())));
    }

    /**
     * Aggregate skill name/description and required connector codes for the given ids.
     * Caller is responsible for any authorization — this method has no ownership check.
     * Soft-deleted skills are filtered out.
     */
    public Map<UUID, AgentSkillWithConnectorsResponse> resolveSkillsById(List<UUID> skillIds) {
        if (skillIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, AgentSkillWithConnectorsResponse> result = new HashMap<>();
        for (Skill skill : skillRepository.findByIdInNotDeleted(skillIds)) {
            result.put(skill.getId(), new AgentSkillWithConnectorsResponse(
                    skill.getId(),
                    skill.getName(),
                    skill.getDescription(),
                    skill.getConnectorCodes()
            ));
        }
        return result;
    }

    @Transactional
    public AgentSkillResponse create(UUID agentId, UUID skillId, UUID userId) {
        return create(agentId, skillId, userId, Map.of());
    }

    /**
     * Bind a skill, recording which instance it means for every connector it declares
     * ({@code requested}: connector code → connection). The reference is not a grant — the tools open
     * through {@code agent_connections} as before; this only fixes «which of the two telegrams».
     */
    @Transactional
    public AgentSkillResponse create(UUID agentId, UUID skillId, UUID userId, Map<String, UUID> requested) {
        verifyAgentOwnership(agentId, userId);
        Skill skill = verifySkillAccessible(skillId, userId);
        requireDeclared(skill, requested);

        AgentSkill agentSkill = AgentSkill.builder()
                .userId(userId)
                .agentId(agentId)
                .skillId(skillId)
                .installedSkillVersion(skill.getVersion())
                .build();

        try {
            agentSkill = agentSkillRepository.save(agentSkill);
        } catch (DataIntegrityViolationException e) {
            throw new ConflictStatusException("Skill is already bound to this agent");
        }

        storeConnections(agentSkill.getId(), skill, userId, requested);

        log.info("Bound skill {} to agent {} for user {}", skillId, agentId, userId);

        SkillResolution resolution = resolveSkills(agentId);
        return AgentSkillResponse.from(agentSkill, skill.getName(),
                statuses(resolution, agentSkill.getId(), skill.getConnectorCodes()), false);
    }

    @Transactional
    public void delete(UUID agentId, UUID skillId, UUID userId) {
        verifyAgentOwnership(agentId, userId);

        AgentSkill agentSkill = agentSkillRepository.findByAgentIdAndSkillId(agentId, skillId)
                .orElseThrow(() -> new NotFoundStatusException("Agent-skill binding not found"));

        if (!agentSkill.getUserId().equals(userId)) {
            throw new NotFoundStatusException("Agent-skill binding not found");
        }

        // Access is not revoked here: the skill never granted it. The connection stays bound until the
        // user unbinds it — that is what «skills and connections are managed separately» means.
        agentSkillRepository.delete(agentSkill);

        log.info("Unbound skill {} from agent {} for user {}", skillId, agentId, userId);
    }

    /**
     * Accept the current version of every skill the agent has: the body is read live at run time, so
     * this only clears {@code needsReinstall} — «yes, I have seen what the author changed».
     */
    @Transactional
    public void markSkillsInstalled(UUID agentId, UUID userId) {
        verifyAgentOwnership(agentId, userId);

        var agentSkills = agentSkillRepository.findByAgentId(agentId);
        var skillIds = agentSkills.stream().map(AgentSkill::getSkillId).collect(Collectors.toSet());

        Map<UUID, Integer> skillVersions = skillIds.isEmpty()
                ? Map.of()
                : skillRepository.findByIdInNotDeleted(skillIds).stream()
                        .collect(Collectors.toMap(Skill::getId, Skill::getVersion));

        for (AgentSkill as : agentSkills) {
            Integer currentVersion = skillVersions.get(as.getSkillId());
            if (currentVersion != null) {
                as.setInstalledSkillVersion(currentVersion);
            }
        }
        agentSkillRepository.saveAll(agentSkills);

        log.info("Marked skills installed at their current version on agent {} for user {}", agentId, userId);
    }

    /**
     * Replace the skill's instance references — «this skill now works with that telegram». Same rules
     * as on binding; the set is replaced whole, so a code left out goes back to having no answer.
     */
    @Transactional
    public AgentSkillResponse replaceConnections(UUID agentId, UUID skillId, UUID userId,
                                                 Map<String, UUID> requested) {
        verifyAgentOwnership(agentId, userId);
        Skill skill = verifySkillAccessible(skillId, userId);
        requireDeclared(skill, requested);

        AgentSkill agentSkill = agentSkillRepository.findByAgentIdAndSkillId(agentId, skillId)
                .orElseThrow(() -> new NotFoundStatusException("Skill is not bound to this agent"));

        agentSkillConnectionRepository.deleteByAgentSkillId(agentSkill.getId());
        storeConnections(agentSkill.getId(), skill, userId, requested);

        SkillResolution resolution = resolveSkills(agentId);
        return AgentSkillResponse.from(agentSkill, skill.getName(),
                statuses(resolution, agentSkill.getId(), skill.getConnectorCodes()), false);
    }

    /**
     * The agent's skills resolved to the instances they point at — the one answer every reader takes
     * its own view of: the run context (only complete skills, all their instances), the skill listing
     * (per code, one representative) and the connection listing (how many skills point here). They used
     * to resolve separately, and the counter, which read the reference rows alone, disagreed with a
     * status that fell back to the code.
     *
     * <p>A declared code with no reference of its own (a binding older than references) resolves to
     * <b>every</b> bound instance of that code — exactly what the tool gate did when it worked by code.
     */
    private SkillResolution resolveSkills(UUID agentId) {
        List<AgentSkill> agentSkills = agentSkillRepository.findByAgentId(agentId);
        if (agentSkills.isEmpty()) {
            return new SkillResolution(List.of(), Set.of(), Map.of());
        }
        Map<UUID, Skill> skills = skillRepository
                .findByIdInNotDeleted(agentSkills.stream().map(AgentSkill::getSkillId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Skill::getId, skill -> skill));

        Map<UUID, Connection> connections = new LinkedHashMap<>();
        Map<String, List<UUID>> boundByCode = new HashMap<>();
        for (Connection connection : connectionRepository.findActiveBoundToAgent(agentId)) {
            connections.put(connection.getId(), connection);
            boundByCode.computeIfAbsent(connection.getConnectorCode(), k -> new ArrayList<>())
                    .add(connection.getId());
        }
        Set<UUID> boundIds = Set.copyOf(connections.keySet());

        Map<UUID, Map<String, UUID>> references = new HashMap<>();
        Set<UUID> referencedIds = new HashSet<>();
        for (AgentSkillConnection link : agentSkillConnectionRepository.findByAgentSkillIdIn(
                agentSkills.stream().map(AgentSkill::getId).toList())) {
            references.computeIfAbsent(link.getAgentSkillId(), k -> new HashMap<>())
                    .put(link.getConnectorCode(), link.getConnectionId());
            referencedIds.add(link.getConnectionId());
        }
        // A referenced instance may be unbound: we still show which one the skill means, so it has to be
        // loaded — «chosen but not open» is a different problem for the user than «nothing chosen».
        referencedIds.removeAll(boundIds);
        if (!referencedIds.isEmpty()) {
            connectionRepository.findByIdInNotDeleted(List.copyOf(referencedIds))
                    .forEach(connection -> connections.put(connection.getId(), connection));
        }

        List<ResolvedSkill> resolved = new ArrayList<>();
        for (AgentSkill agentSkill : agentSkills) {
            Skill skill = skills.get(agentSkill.getSkillId());
            if (skill == null) {
                continue;
            }
            Map<String, UUID> skillReferences = references.getOrDefault(agentSkill.getId(), Map.of());
            Map<String, List<UUID>> instancesByCode = new LinkedHashMap<>();
            boolean complete = true;
            for (String code : declaredCodes(skill)) {
                UUID referenced = skillReferences.get(code);
                List<UUID> instances = referenced != null
                        ? List.of(referenced)
                        : boundByCode.getOrDefault(code, List.of());
                instancesByCode.put(code, instances);
                if (instances.isEmpty() || !boundIds.containsAll(instances)) {
                    complete = false;
                }
            }
            resolved.add(new ResolvedSkill(agentSkill.getId(), agentSkill.getSkillId(), instancesByCode, complete));
        }
        return new SkillResolution(resolved, boundIds, connections);
    }

    /** @see #resolveSkills */
    private record ResolvedSkill(UUID agentSkillId, UUID skillId,
                                 Map<String, List<UUID>> instancesByCode, boolean complete) {
    }

    /** @see #resolveSkills */
    private record SkillResolution(List<ResolvedSkill> skills, Set<UUID> boundIds,
                                   Map<UUID, Connection> connections) {
    }

    /**
     * The agent's <b>satisfied</b> skills and the instances each works with: skillId → connection ids.
     * A skill is absent when any connector it declares has no reachable instance — it is not given to
     * the agent at all, because its body would otherwise promise tools that are not there. The union of
     * the values is the tool gate.
     */
    public Map<UUID, Set<UUID>> satisfiedSkillInstances(UUID agentId) {
        Map<UUID, Set<UUID>> satisfied = new LinkedHashMap<>();
        for (ResolvedSkill skill : resolveSkills(agentId).skills()) {
            if (skill.complete()) {
                satisfied.put(skill.skillId(), skill.instancesByCode().values().stream()
                        .flatMap(List::stream).collect(Collectors.toCollection(LinkedHashSet::new)));
            } else {
                log.debug("Skill {} is not satisfied for agent {} — not delivered", skill.skillId(), agentId);
            }
        }
        return satisfied;
    }

    /**
     * How many of the agent's skills point at each connection — the «used by» counter of the listing.
     * Unsatisfied skills count too: the number answers «what points here», and a skill that is broken
     * for another reason still means this instance is not dead weight.
     */
    public Map<UUID, Long> skillReferencesByConnection(UUID agentId) {
        Map<UUID, Long> counts = new HashMap<>();
        for (ResolvedSkill skill : resolveSkills(agentId).skills()) {
            skill.instancesByCode().values().stream()
                    .flatMap(List::stream)
                    .distinct()
                    .forEach(connectionId -> counts.merge(connectionId, 1L, Long::sum));
        }
        return counts;
    }

    /**
     * Codes the skill declares, without repeats. Nothing forbids a skill from listing the same connector
     * twice, and one instance is one answer — a second row for the same code would only break the
     * uniqueness of {@code (agent_skill_id, connector_code)} at flush time.
     */
    private static Collection<String> declaredCodes(Skill skill) {
        return new LinkedHashSet<>(skill.getConnectorCodes());
    }

    private static void requireDeclared(Skill skill, Map<String, UUID> requested) {
        for (String code : requested.keySet()) {
            if (!skill.getConnectorCodes().contains(code)) {
                throw new BadRequestStatusException("Connector " + code + " is not declared by the skill");
            }
        }
    }

    private void storeConnections(UUID agentSkillId, Skill skill, UUID userId, Map<String, UUID> requested) {
        List<AgentSkillConnection> rows = new ArrayList<>();
        for (String code : declaredCodes(skill)) {
            resolveConnection(code, requested.get(code), userId).ifPresent(connectionId ->
                    rows.add(AgentSkillConnection.builder()
                            .agentSkillId(agentSkillId)
                            .connectorCode(code)
                            .connectionId(connectionId)
                            .build()));
        }
        agentSkillConnectionRepository.saveAll(rows);
    }

    /**
     * Which instance the skill means for one connector code. Internal: forced — one mode row per user,
     * and the client cannot even learn its id before the first binding, so the server answers for it
     * (a mismatching id sent anyway is an error, not a silent correction). External: the choice is
     * required, there is no sane default among several accounts. Unknown code (a skill declaring a
     * connector that no longer exists): no row at all — it reads as «not satisfied» rather than as a
     * choice waiting to be made, and binding the skill must not fail because of it.
     */
    private Optional<UUID> resolveConnection(String code, UUID requested, UUID userId) {
        return switch (connectionBindingService.kindOf(code)) {
            case INTERNAL -> {
                UUID modeConnectionId = connectionBindingService.ensureModeConnection(userId, code).getId();
                if (requested != null && !requested.equals(modeConnectionId)) {
                    throw new BadRequestStatusException(
                            "Connector " + code + " has a single instance per user: " + modeConnectionId);
                }
                yield Optional.of(modeConnectionId);
            }
            case EXTERNAL -> {
                if (requested == null) {
                    throw new BadRequestStatusException("Choose the instance for connector " + code);
                }
                Connection connection = connectionRepository.findByIdAndUserIdNotDeleted(requested, userId)
                        .orElseThrow(() -> new BadRequestStatusException("Connection not found: " + requested));
                if (!connection.getConnectorCode().equals(code)) {
                    throw new BadRequestStatusException(
                            "Connection " + requested + " is not an instance of " + code);
                }
                yield Optional.of(connection.getId());
            }
            case UNKNOWN -> {
                log.warn("Skill declares unknown connector '{}' — no instance can be chosen", code);
                yield Optional.empty();
            }
        };
    }

    /**
     * The listing view of one skill's resolution: per declared code, the instance it means (one
     * representative — the listing shows a choice, not a set) and whether the agent can reach it.
     */
    private List<SkillConnectorStatus> statuses(SkillResolution resolution, UUID agentSkillId,
                                                List<String> connectorCodes) {
        Map<String, List<UUID>> instancesByCode = resolution.skills().stream()
                .filter(skill -> skill.agentSkillId().equals(agentSkillId))
                .findFirst()
                .map(ResolvedSkill::instancesByCode)
                .orElse(Map.of());

        List<SkillConnectorStatus> statuses = new ArrayList<>();
        for (String code : connectorCodes) {
            List<UUID> instances = instancesByCode.getOrDefault(code, List.of());
            UUID connectionId = instances.isEmpty() ? null : instances.get(0);
            Connection connection = connectionId == null ? null : resolution.connections().get(connectionId);
            statuses.add(new SkillConnectorStatus(
                    code,
                    connectionId,
                    connection != null ? displayName(connection) : null,
                    connectionBindingService.kindOf(code) == ConnectorKind.INTERNAL,
                    connectionId != null && resolution.boundIds().contains(connectionId)));
        }
        return statuses;
    }

    private static String displayName(Connection connection) {
        return connection.getName() != null && !connection.getName().isBlank()
                ? connection.getName() : connection.getFullCode();
    }

    private void verifyAgentOwnership(UUID agentId, UUID userId) {
        var agent = agentRepository.findById(agentId)
                .orElseThrow(() -> new NotFoundStatusException("Agent not found"));
        if (!agent.getUserId().equals(userId)) {
            throw new NotFoundStatusException("Agent not found");
        }
    }

    /** A skill may be bound if it is the user's own or public — no clone required. */
    private Skill verifySkillAccessible(UUID skillId, UUID userId) {
        var skill = skillRepository.findByIdNotDeleted(skillId)
                .orElseThrow(() -> new NotFoundStatusException("Skill not found"));
        if (!skill.getUserId().equals(userId) && !skill.getIsPublic()) {
            throw new ForbiddenStatusException("Access denied");
        }
        return skill;
    }
}
