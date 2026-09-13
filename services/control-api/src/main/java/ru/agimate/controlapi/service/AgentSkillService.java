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
import ru.agimate.controlapi.abac.SkillPolicySync;
import ru.agimate.controlapi.connectors.core.ConnectorRegistry;
import ru.agimate.controlapi.connectors.core.IntegrationConnectorHandler;
import ru.agimate.controlapi.controller.agent.dto.AgentSkillWithConnectorsResponse;
import ru.agimate.controlapi.controller.manage.dto.AgentSkillResponse;
import ru.agimate.controlapi.controller.manage.dto.CredentialFieldResponse;
import ru.agimate.controlapi.controller.manage.dto.SkillBindingPlanResponse;
import ru.agimate.controlapi.controller.manage.dto.SkillConnectorStatus;
import ru.agimate.controlapi.controller.manage.dto.SkillConnectorStatus.ConnectionMatch;
import ru.agimate.controlapi.database.entities.AgentSkill;
import ru.agimate.controlapi.database.entities.AgentSkillConnection;
import ru.agimate.controlapi.database.entities.Connection;
import ru.agimate.controlapi.database.entities.Connector;
import ru.agimate.controlapi.database.entities.Skill;
import ru.agimate.controlapi.database.enums.DefinitionBinding;
import ru.agimate.controlapi.database.enums.Disclosure;
import ru.agimate.controlapi.database.model.ConnectorRequirement;
import ru.agimate.controlapi.database.repositories.AgentRepository;
import ru.agimate.controlapi.database.repositories.AgentSkillConnectionRepository;
import ru.agimate.controlapi.database.repositories.AgentSkillRepository;
import ru.agimate.controlapi.database.repositories.ConnectionRepository;
import ru.agimate.controlapi.database.repositories.ConnectionToolRepository;
import ru.agimate.controlapi.database.repositories.ConnectionTriggerRepository;
import ru.agimate.controlapi.database.repositories.ConnectorRepository;
import ru.agimate.controlapi.database.repositories.SkillRepository;
import ru.agimate.controlapi.service.connection.ConnectionBindingService;
import ru.agimate.controlapi.service.connection.ConnectionBindingService.ConnectorKind;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
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

    /**
     * Why one requirement of a skill is or is not met. Replaces the boolean the gate used to keep:
     * the reason is what the agent is told and what the wizard renders, and it has to be the same
     * reason in both places, so it is computed once here.
     */
    public enum RequirementState {

        /** An instance is chosen, bound, authorised and brings tools. */
        OK,

        /** Nothing answers the requirement: no reference, and no bound instance matches by identity or code. */
        NOT_CHOSEN,

        /** The instance the skill means is not bound to this agent, switched off or gone. */
        NOT_BOUND,

        /** Bound, but the authorisation is dead or was never finished — every call would answer 401. */
        UNAUTHORIZED,

        /**
         * A DYNAMIC instance that brought neither tools nor triggers: discovery never succeeded (the
         * MCP server was down when the instance was created). Both halves matter — an {@code app} row
         * is DYNAMIC too and may legitimately carry triggers alone.
         */
        NO_CAPABILITIES,

        /** The skill declares a connector that no longer exists in the registry. */
        UNKNOWN_CONNECTOR
    }

    /**
     * A skill the agent does not get. Carried to the run context so the catalogue can say so instead
     * of the skill silently not existing — the body is still withheld, only the reason travels.
     */
    public record WithheldSkill(UUID skillId, String name, String description, List<String> connectorCodes,
                                List<Blocker> blockers) {

        /** One unmet requirement: the key the skill declares, the connector it wants, and why it is not met. */
        public record Blocker(String key, String code, RequirementState state) {
        }
    }

    /**
     * The gate's answer in one pass: what reaches the agent (skillId → the instances its tools come
     * from) and what does not, with reasons. Two views of one resolution — computing them separately
     * is how the listing and the runtime used to disagree.
     */
    public record SkillGate(Map<UUID, Set<UUID>> satisfied, List<WithheldSkill> withheld) {
    }

    private static final int MAX_PAGE_SIZE = 100;

    private final AgentSkillRepository agentSkillRepository;
    private final AgentRepository agentRepository;
    private final SkillRepository skillRepository;
    private final ConnectionRepository connectionRepository;
    private final ConnectorRepository connectorRepository;
    private final ConnectionToolRepository connectionToolRepository;
    private final ConnectionTriggerRepository connectionTriggerRepository;
    private final AgentSkillConnectionRepository agentSkillConnectionRepository;
    private final ConnectionBindingService connectionBindingService;
    private final ConnectorRegistry connectorRegistry;
    private final SkillPolicySync policySync;

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
        StatusContext context = statusContext(userId, agentId);

        return agentSkills.map(as -> {
            Skill skill = skillMap.get(as.getSkillId());
            String name = skill != null ? skill.getName() : null;
            boolean needsReinstall = skill != null
                    && (as.getInstalledSkillVersion() == null || skill.getVersion() > as.getInstalledSkillVersion());
            List<SkillConnectorStatus> connectors = skill == null ? List.of()
                    : statuses(resolution, as.getId(), skill, context);
            return AgentSkillResponse.from(as, name, connectors, needsReinstall,
                    skill != null ? skill.getDisclosure() : Disclosure.EAGER);
        });
    }

    public Page<AgentSkillWithConnectorsResponse> getAgentSkillsWithConnectors(UUID agentId, UUID userId, int page, int size) {
        verifyAgentOwnership(agentId, userId);
        PageRequest pageRequest = PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE), Sort.by("createdAt").descending());
        Page<AgentSkill> bindings = agentSkillRepository.findByAgentId(agentId, pageRequest);

        Map<UUID, AgentSkillWithConnectorsResponse> resolved = resolveSkills(bindings.getContent());

        return bindings.map(binding -> resolved.getOrDefault(binding.getSkillId(),
                new AgentSkillWithConnectorsResponse(binding.getSkillId(), null, null, List.of(), Disclosure.EAGER)));
    }

    /**
     * Aggregate skill name/description, required connector codes and the effective disclosure axis
     * for the given bindings, keyed by skill id. Takes the bindings rather than skill ids because the
     * axis is theirs to override — this is the one place the override meets the skill's default, so
     * every reader (the run context, the listings) sees the same effective value.
     * Caller is responsible for any authorization — this method has no ownership check.
     * Soft-deleted skills are filtered out.
     */
    public Map<UUID, AgentSkillWithConnectorsResponse> resolveSkills(List<AgentSkill> bindings) {
        if (bindings.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Disclosure> overrides = new HashMap<>();
        for (AgentSkill binding : bindings) {
            if (binding.getDisclosure() != null) {
                overrides.put(binding.getSkillId(), binding.getDisclosure());
            }
        }
        Map<UUID, AgentSkillWithConnectorsResponse> result = new HashMap<>();
        for (Skill skill : skillRepository.findByIdInNotDeleted(bindings.stream().map(AgentSkill::getSkillId).toList())) {
            result.put(skill.getId(), new AgentSkillWithConnectorsResponse(
                    skill.getId(),
                    skill.getName(),
                    skill.getDescription(),
                    skill.getConnectorCodes(),
                    overrides.getOrDefault(skill.getId(), skill.getDisclosure())
            ));
        }
        return result;
    }

    /**
     * What binding the skill would take, before it is bound: every requirement resolved the way an
     * unreferenced one is (by identity, else by code), with the user's fitting connections and the
     * form to create one. The same statuses the listing shows afterwards.
     */
    public SkillBindingPlanResponse plan(UUID agentId, UUID skillId, UUID userId) {
        verifyAgentOwnership(agentId, userId);
        Skill skill = verifySkillAccessible(skillId, userId);
        Map<UUID, Connection> bound = boundConnections(agentId);
        Map<String, List<UUID>> instancesByKey = resolveInstances(skill, Map.of(), bound);
        Requirements requirements = requirements(skill, instancesByKey, bound.keySet(), bound,
                broughtNothing(bound.values()));
        SkillResolution resolution = new SkillResolution(
                List.of(new ResolvedSkill(null, skillId, instancesByKey, requirements.states(), requirements.fitByKey())),
                bound.keySet(), bound, Map.of(skillId, skill));
        return SkillBindingPlanResponse.of(skillId, skill.getName(),
                statuses(resolution, null, skill, statusContext(userId, agentId)));
    }

    @Transactional
    public AgentSkillResponse create(UUID agentId, UUID skillId, UUID userId) {
        return create(agentId, skillId, userId, Map.of(), null);
    }

    /**
     * Bind a skill, recording which instance it means for every requirement it declares
     * ({@code requested}: requirement key → connection). The reference is not a grant — the tools open
     * through {@code agent_connections} as before; this only fixes «which of the two telegrams». The
     * rules the skill declares are written onto the bindings its instances already have.
     *
     * @param disclosure the binding's override of the skill's axis; {@code null} — the skill's own applies
     */
    @Transactional
    public AgentSkillResponse create(UUID agentId, UUID skillId, UUID userId, Map<String, UUID> requested,
                                     Disclosure disclosure) {
        verifyAgentOwnership(agentId, userId);
        Skill skill = verifySkillAccessible(skillId, userId);
        requireDeclared(skill, requested);

        AgentSkill agentSkill = AgentSkill.builder()
                .userId(userId)
                .agentId(agentId)
                .skillId(skillId)
                .installedSkillVersion(skill.getVersion())
                .disclosure(disclosure)
                .build();

        try {
            agentSkill = agentSkillRepository.save(agentSkill);
        } catch (DataIntegrityViolationException e) {
            throw new ConflictStatusException("Skill is already bound to this agent");
        }

        applyPolicies(agentId, skill, storeConnections(agentSkill.getId(), skill, userId, requested));

        log.info("Bound skill {} to agent {} for user {}", skillId, agentId, userId);
        return response(agentSkill, skill, userId);
    }

    /**
     * The binding's disclosure override — the same skill in the prompt of one agent, on demand for
     * another. The instance references are not touched.
     *
     * @param disclosure {@code null} drops the override, the skill's own axis applies again
     */
    @Transactional
    public AgentSkillResponse updateDisclosure(UUID agentId, UUID skillId, UUID userId, Disclosure disclosure) {
        verifyAgentOwnership(agentId, userId);
        Skill skill = verifySkillAccessible(skillId, userId);
        AgentSkill agentSkill = agentSkillRepository.findByAgentIdAndSkillId(agentId, skillId)
                .orElseThrow(() -> new NotFoundStatusException("Skill is not bound to this agent"));

        agentSkill.setDisclosure(disclosure);
        agentSkill = agentSkillRepository.save(agentSkill);
        return response(agentSkill, skill, userId);
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
        // user unbinds it — that is what «skills and connections are managed separately» means. The
        // rules the skill wrote are its own and leave with it.
        policySync.remove(agentId, skillId);
        agentSkillRepository.delete(agentSkill);

        log.info("Unbound skill {} from agent {} for user {}", skillId, agentId, userId);
    }

    /**
     * Accept the current version of every skill the agent has: the body is read live at run time, so
     * for the text this only clears {@code needsReinstall} — «yes, I have seen what the author
     * changed». For the rules it is a reset to what the author declares now, a row the user deleted
     * included; a row the user edited is theirs and stays.
     */
    @Transactional
    public void markSkillsInstalled(UUID agentId, UUID userId) {
        verifyAgentOwnership(agentId, userId);

        var agentSkills = agentSkillRepository.findByAgentId(agentId);
        var skillIds = agentSkills.stream().map(AgentSkill::getSkillId).collect(Collectors.toSet());

        Map<UUID, Skill> skills = skillIds.isEmpty()
                ? Map.of()
                : skillRepository.findByIdInNotDeleted(skillIds).stream()
                        .collect(Collectors.toMap(Skill::getId, skill -> skill));

        for (AgentSkill as : agentSkills) {
            Skill skill = skills.get(as.getSkillId());
            if (skill != null) {
                as.setInstalledSkillVersion(skill.getVersion());
                applyPolicies(agentId, skill, agentSkillConnectionRepository.findByAgentSkillId(as.getId()));
            }
        }
        agentSkillRepository.saveAll(agentSkills);

        log.info("Marked skills installed at their current version on agent {} for user {}", agentId, userId);
    }

    /**
     * Replace the skill's instance references — «this skill now works with that telegram». Same rules
     * as on binding; the set is replaced whole, so a key left out goes back to having no answer.
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
        applyPolicies(agentId, skill, storeConnections(agentSkill.getId(), skill, userId, requested));
        return response(agentSkill, skill, userId);
    }

    private AgentSkillResponse response(AgentSkill agentSkill, Skill skill, UUID userId) {
        SkillResolution resolution = resolveSkills(agentSkill.getAgentId());
        return AgentSkillResponse.from(agentSkill, skill.getName(),
                statuses(resolution, agentSkill.getId(), skill, statusContext(userId, agentSkill.getAgentId())),
                false, skill.getDisclosure());
    }

    /**
     * The agent's skills resolved to the instances they point at — the one answer every reader takes
     * its own view of: the run context (only complete skills, all their instances), the skill listing
     * (per key, one representative) and the connection listing (how many skills point here). They used
     * to resolve separately, and the counter, which read the reference rows alone, disagreed with a
     * status that fell back to the code.
     */
    private SkillResolution resolveSkills(UUID agentId) {
        List<AgentSkill> agentSkills = agentSkillRepository.findByAgentId(agentId);
        if (agentSkills.isEmpty()) {
            return new SkillResolution(List.of(), Set.of(), Map.of(), Map.of());
        }
        Map<UUID, Skill> skills = skillRepository
                .findByIdInNotDeleted(agentSkills.stream().map(AgentSkill::getSkillId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Skill::getId, skill -> skill));

        Map<UUID, Connection> connections = boundConnections(agentId);
        Map<UUID, Connection> bound = Map.copyOf(connections);
        Set<UUID> boundIds = bound.keySet();

        Map<UUID, Map<String, UUID>> references = new HashMap<>();
        Set<UUID> referencedIds = new HashSet<>();
        for (AgentSkillConnection link : agentSkillConnectionRepository.findByAgentSkillIdIn(
                agentSkills.stream().map(AgentSkill::getId).toList())) {
            references.computeIfAbsent(link.getAgentSkillId(), k -> new HashMap<>())
                    .put(link.getConnectorKey(), link.getConnectionId());
            referencedIds.add(link.getConnectionId());
        }
        // A referenced instance may be unbound: we still show which one the skill means, so it has to be
        // loaded — «chosen but not open» is a different problem for the user than «nothing chosen».
        referencedIds.removeAll(boundIds);
        if (!referencedIds.isEmpty()) {
            connectionRepository.findByIdInNotDeleted(List.copyOf(referencedIds))
                    .forEach(connection -> connections.put(connection.getId(), connection));
        }

        Set<UUID> broughtNothing = broughtNothing(bound.values());
        List<ResolvedSkill> resolved = new ArrayList<>();
        for (AgentSkill agentSkill : agentSkills) {
            Skill skill = skills.get(agentSkill.getSkillId());
            if (skill == null) {
                continue;
            }
            Map<String, List<UUID>> instancesByKey = resolveInstances(skill,
                    references.getOrDefault(agentSkill.getId(), Map.of()), bound);
            Requirements requirements = requirements(skill, instancesByKey, boundIds, connections, broughtNothing);
            resolved.add(new ResolvedSkill(agentSkill.getId(), agentSkill.getSkillId(), instancesByKey,
                    requirements.states(), requirements.fitByKey()));
        }
        return new SkillResolution(resolved, boundIds, connections, skills);
    }

    /**
     * Per requirement key, the instances it means. A reference answers outright. Without one (a
     * binding older than the reference, or a key the author added later) the requirement's own
     * identity decides: the bound instances of the code whose {@code sub_code} it names — a declared
     * server URL must not resolve to another server of the same connector; and only a requirement
     * that names none falls back to <b>every</b> bound instance of the code, exactly what the tool gate
     * did when it worked by code.
     */
    private Map<String, List<UUID>> resolveInstances(Skill skill, Map<String, UUID> references,
                                                     Map<UUID, Connection> bound) {
        Map<String, List<Connection>> boundByCode = new HashMap<>();
        for (Connection connection : bound.values()) {
            boundByCode.computeIfAbsent(connection.getConnectorCode(), k -> new ArrayList<>()).add(connection);
        }
        Map<String, List<UUID>> instancesByKey = new LinkedHashMap<>();
        for (ConnectorRequirement requirement : skill.getConnectors()) {
            UUID referenced = references.get(requirement.key());
            List<UUID> instances;
            if (referenced != null) {
                instances = List.of(referenced);
            } else {
                String identity = identityOf(requirement).orElse(null);
                instances = boundByCode.getOrDefault(requirement.code(), List.of()).stream()
                        .filter(connection -> identity == null || identity.equals(connection.getSubCode()))
                        .map(Connection::getId)
                        .toList();
            }
            instancesByKey.put(requirement.key(), instances);
        }
        return instancesByKey;
    }

    /** One skill's requirements resolved: the state of each, and the instances that actually serve it. */
    private record Requirements(Map<String, RequirementState> states, Map<String, List<UUID>> fitByKey) {
    }

    /** The state of every requirement the skill declares, in declaration order, and what serves it. */
    private Requirements requirements(Skill skill, Map<String, List<UUID>> instancesByKey,
                                      Set<UUID> boundIds, Map<UUID, Connection> connections,
                                      Set<UUID> broughtNothing) {
        Map<String, RequirementState> states = new LinkedHashMap<>();
        Map<String, List<UUID>> fitByKey = new LinkedHashMap<>();
        for (ConnectorRequirement requirement : skill.getConnectors()) {
            List<UUID> candidates = instancesByKey.getOrDefault(requirement.key(), List.of());
            List<UUID> fit = candidates.stream()
                    .filter(id -> isFit(id, boundIds, connections, broughtNothing))
                    .toList();
            states.put(requirement.key(), stateOf(requirement, candidates, fit, boundIds, connections, broughtNothing));
            fitByKey.put(requirement.key(), fit);
        }
        return new Requirements(states, fitByKey);
    }

    /** Bound, authorised, and carrying something to offer. */
    private static boolean isFit(UUID id, Set<UUID> boundIds, Map<UUID, Connection> connections,
                                 Set<UUID> broughtNothing) {
        Connection connection = connections.get(id);
        return boundIds.contains(id)
                && (connection == null || connection.isUsable())
                && !broughtNothing.contains(id);
    }

    /**
     * Why one requirement is or is not met. <b>One fit instance is enough</b>: where the requirement
     * names an instance there is only one candidate anyway, and where it names none the fallback
     * deliberately means «any bound instance of this code» — letting one broken instance disable a
     * skill the agent could work with would be a regression on the very case the fallback exists for.
     *
     * <p>When nothing is fit, the reason is the first candidate's, checked in the order the user fixes
     * them in: there is no point reporting a dead token on an instance that is not bound yet.
     */
    private RequirementState stateOf(ConnectorRequirement requirement, List<UUID> candidates, List<UUID> fit,
                                     Set<UUID> boundIds, Map<UUID, Connection> connections,
                                     Set<UUID> broughtNothing) {
        if (connectionBindingService.kindOf(requirement.code()) == ConnectorKind.UNKNOWN) {
            return RequirementState.UNKNOWN_CONNECTOR;
        }
        if (candidates.isEmpty()) {
            return RequirementState.NOT_CHOSEN;
        }
        if (!fit.isEmpty()) {
            return RequirementState.OK;
        }
        if (candidates.stream().anyMatch(id -> !boundIds.contains(id))) {
            return RequirementState.NOT_BOUND;
        }
        if (candidates.stream().anyMatch(id -> {
            Connection connection = connections.get(id);
            return connection != null && !connection.isUsable();
        })) {
            return RequirementState.UNAUTHORIZED;
        }
        return RequirementState.NO_CAPABILITIES;
    }

    /**
     * Bound DYNAMIC instances that brought nothing — neither tools nor triggers. Their discovery never
     * succeeded (the MCP server was down when the instance was created and the listener only logged
     * it), so a skill pointing here would ship a body promising capabilities that do not exist. Both
     * halves are needed: {@code app} is DYNAMIC too, and an app that only raises events is not broken.
     * Two queries for the lot, and none at all when nothing DYNAMIC is bound.
     */
    private Set<UUID> broughtNothing(Collection<Connection> bound) {
        if (bound.isEmpty()) {
            return Set.of();
        }
        // By the codes in hand rather than the whole catalogue: this runs on every context build.
        Set<String> dynamicCodes = new HashSet<>();
        connectorRepository.findAllById(bound.stream().map(Connection::getConnectorCode).collect(Collectors.toSet()))
                .forEach(connector -> {
                    if (connector.getDefinitionBinding() == DefinitionBinding.DYNAMIC) {
                        dynamicCodes.add(connector.getCode());
                    }
                });
        List<UUID> dynamic = bound.stream()
                .filter(connection -> dynamicCodes.contains(connection.getConnectorCode()))
                .map(Connection::getId)
                .toList();
        if (dynamic.isEmpty()) {
            return Set.of();
        }
        Set<UUID> alive = new HashSet<>(connectionToolRepository.findIdsWithActiveTools(dynamic));
        alive.addAll(connectionTriggerRepository.findIdsWithActiveTriggers(dynamic));
        return dynamic.stream().filter(id -> !alive.contains(id)).collect(Collectors.toSet());
    }

    private Map<String, Connector> catalogue() {
        Map<String, Connector> catalogue = new HashMap<>();
        connectorRepository.findAll().forEach(connector -> catalogue.put(connector.getCode(), connector));
        return catalogue;
    }

    private Map<UUID, Connection> boundConnections(UUID agentId) {
        Map<UUID, Connection> connections = new LinkedHashMap<>();
        for (Connection connection : connectionRepository.findActiveBoundToAgent(agentId)) {
            connections.put(connection.getId(), connection);
        }
        return connections;
    }

    /** The {@code sub_code} the requirement's params name, when the connector can tell without the network. */
    private Optional<String> identityOf(ConnectorRequirement requirement) {
        if (requirement.params() == null) {
            return Optional.empty();
        }
        return connectorRegistry.findIntegrationHandler(requirement.code())
                .flatMap(handler -> handler.identifierOf(requirement.params()));
    }

    /** @see #resolveSkills */
    private record ResolvedSkill(UUID agentSkillId, UUID skillId,
                                 Map<String, List<UUID>> instancesByKey,
                                 Map<String, RequirementState> states,
                                 Map<String, List<UUID>> fitByKey) {

        /** Every requirement met — a skill declaring none is complete, as it always was. */
        boolean complete() {
            return states.values().stream().allMatch(state -> state == RequirementState.OK);
        }
    }

    /** @see #resolveSkills */
    private record SkillResolution(List<ResolvedSkill> skills, Set<UUID> boundIds,
                                   Map<UUID, Connection> connections, Map<UUID, Skill> definitions) {
    }

    /**
     * The gate in one pass. A skill is withheld when any requirement it declares is unmet — its body
     * would otherwise promise tools that are not there — and the reason travels with it, so the
     * catalogue can say «unavailable, and why» instead of the skill silently not existing. The union
     * of the satisfied instances is the tool gate.
     */
    public SkillGate gate(UUID agentId) {
        SkillResolution resolution = resolveSkills(agentId);
        Map<UUID, Set<UUID>> satisfied = new LinkedHashMap<>();
        List<WithheldSkill> withheld = new ArrayList<>();
        for (ResolvedSkill skill : resolution.skills()) {
            if (skill.complete()) {
                // The fit ones, not every candidate: a code-wide fallback may resolve to a broken
                // instance alongside a working one, and only the working one may open its tools.
                satisfied.put(skill.skillId(), skill.fitByKey().values().stream()
                        .flatMap(List::stream).collect(Collectors.toCollection(LinkedHashSet::new)));
                continue;
            }
            Skill definition = resolution.definitions().get(skill.skillId());
            if (definition == null) {
                continue;
            }
            List<WithheldSkill.Blocker> blockers = skill.states().entrySet().stream()
                    .filter(state -> state.getValue() != RequirementState.OK)
                    .map(state -> new WithheldSkill.Blocker(state.getKey(),
                            codeOf(definition, state.getKey()), state.getValue()))
                    .toList();
            log.debug("Skill {} is withheld from agent {}: {}", skill.skillId(), agentId, blockers);
            withheld.add(new WithheldSkill(skill.skillId(), definition.getName(), definition.getDescription(),
                    definition.getConnectorCodes(), blockers));
        }
        return new SkillGate(satisfied, withheld);
    }

    /** The connector a requirement key stands for; the key itself when the declaration is gone. */
    private static String codeOf(Skill skill, String key) {
        ConnectorRequirement requirement = skill.requirement(key);
        return requirement != null ? requirement.code() : key;
    }

    /**
     * How many of the agent's skills point at each connection — the «used by» counter of the listing.
     * Unsatisfied skills count too: the number answers «what points here», and a skill that is broken
     * for another reason still means this instance is not dead weight.
     */
    public Map<UUID, Long> skillReferencesByConnection(UUID agentId) {
        Map<UUID, Long> counts = new HashMap<>();
        for (ResolvedSkill skill : resolveSkills(agentId).skills()) {
            skill.instancesByKey().values().stream()
                    .flatMap(List::stream)
                    .distinct()
                    .forEach(connectionId -> counts.merge(connectionId, 1L, Long::sum));
        }
        return counts;
    }

    private static void requireDeclared(Skill skill, Map<String, UUID> requested) {
        for (String key : requested.keySet()) {
            if (skill.requirement(key) == null) {
                throw new BadRequestStatusException("Connector " + key + " is not declared by the skill");
            }
        }
    }

    private List<AgentSkillConnection> storeConnections(UUID agentSkillId, Skill skill, UUID userId,
                                                        Map<String, UUID> requested) {
        List<AgentSkillConnection> rows = new ArrayList<>();
        for (ConnectorRequirement requirement : skill.getConnectors()) {
            resolveConnection(requirement, requested.get(requirement.key()), userId).ifPresent(connectionId ->
                    rows.add(AgentSkillConnection.builder()
                            .agentSkillId(agentSkillId)
                            .connectorKey(requirement.key())
                            .connectionId(connectionId)
                            .build()));
        }
        agentSkillConnectionRepository.saveAll(rows);
        return rows;
    }

    /** The skill's rules onto the bindings of the instances it references; unbound instances wait for the wizard's order. */
    private void applyPolicies(UUID agentId, Skill skill, List<AgentSkillConnection> references) {
        for (AgentSkillConnection reference : references) {
            ConnectorRequirement requirement = skill.requirement(reference.getConnectorKey());
            if (requirement != null && requirement.hasRules()) {
                policySync.apply(agentId, reference.getConnectionId(), skill.getId(), requirement);
            }
        }
    }

    /**
     * Which instance the skill means for one requirement. Internal: forced — one mode row per user,
     * and the client cannot even learn its id before the first binding, so the server answers for it
     * (a mismatching id sent anyway is an error, not a silent correction). External without a choice:
     * no row — the skill is bound unsatisfied, which is what creating an agent from a preset does, and
     * the wizard (or the identity fallback, once the declared server is bound) finishes it; there is no
     * sane default among several accounts, so the server never picks one. Unknown code (a skill
     * declaring a connector that no longer exists): no row either — it reads as «not satisfied» rather
     * than as a choice waiting to be made, and binding the skill must not fail because of it.
     */
    private Optional<UUID> resolveConnection(ConnectorRequirement requirement, UUID requested, UUID userId) {
        String code = requirement.code();
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
                    yield Optional.empty();
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

    /** What the statuses need beyond the resolution: the user's connections to offer, the catalogue for names. */
    private record StatusContext(UUID agentId, Map<String, List<Connection>> userByCode, Map<String, Connector> catalogue) {
    }

    private StatusContext statusContext(UUID userId, UUID agentId) {
        Map<String, List<Connection>> userByCode = new HashMap<>();
        for (Connection connection : connectionRepository.findByUserIdNotDeleted(userId)) {
            userByCode.computeIfAbsent(connection.getConnectorCode(), k -> new ArrayList<>()).add(connection);
        }
        return new StatusContext(agentId, userByCode, catalogue());
    }

    /**
     * The listing view of one skill's resolution: per requirement, the instance it means (one
     * representative — the listing shows a choice, not a set), whether the agent can reach it, and
     * what the wizard would offer instead.
     *
     * @param agentSkillId {@code null} for the plan of a skill not yet bound
     */
    private List<SkillConnectorStatus> statuses(SkillResolution resolution, UUID agentSkillId, Skill skill,
                                                StatusContext context) {
        Optional<ResolvedSkill> resolved = resolution.skills().stream()
                .filter(candidate -> agentSkillId == null
                        ? candidate.skillId().equals(skill.getId())
                        : agentSkillId.equals(candidate.agentSkillId()))
                .findFirst();
        Map<String, List<UUID>> instancesByKey = resolved.map(ResolvedSkill::instancesByKey).orElse(Map.of());
        // Read, not re-derived: the listing and the run context must not be able to disagree about
        // what «satisfied» means — that divergence is exactly what resolveSkills was consolidated against.
        Map<String, RequirementState> states = resolved.map(ResolvedSkill::states).orElse(Map.of());

        List<SkillConnectorStatus> statuses = new ArrayList<>();
        for (ConnectorRequirement requirement : skill.getConnectors()) {
            List<UUID> instances = instancesByKey.getOrDefault(requirement.key(), List.of());
            UUID connectionId = instances.isEmpty() ? null : instances.get(0);
            Connection connection = connectionId == null ? null : resolution.connections().get(connectionId);
            boolean internal = connectionBindingService.kindOf(requirement.code()) == ConnectorKind.INTERNAL;
            RequirementState state = states.getOrDefault(requirement.key(), RequirementState.NOT_CHOSEN);
            boolean satisfied = state == RequirementState.OK;
            Optional<IntegrationConnectorHandler> handler = connectorRegistry.findIntegrationHandler(requirement.code());
            String identity = identityOf(requirement).orElse(null);
            statuses.add(new SkillConnectorStatus(
                    requirement.key(),
                    requirement.code(),
                    title(requirement, context.catalogue().get(requirement.code())),
                    internal,
                    requirement.params(),
                    handler.map(AgentSkillService::credentialFields).orElse(null),
                    identity,
                    matches(requirement, identity, context, resolution.boundIds()),
                    connectionId,
                    connection != null ? displayName(connection) : null,
                    state,
                    satisfied,
                    SkillPolicySync.desired(requirement),
                    satisfied && requirement.hasRules()
                            ? policySync.conflicts(context.agentId(), connectionId, skill.getId(), requirement)
                            : List.of()));
        }
        return statuses;
    }

    /** The user's connections that fit: by identity when the params name one, else every instance of the code. */
    private static List<ConnectionMatch> matches(ConnectorRequirement requirement, String identity,
                                                 StatusContext context, Set<UUID> boundIds) {
        return context.userByCode().getOrDefault(requirement.code(), List.of()).stream()
                .filter(connection -> identity == null || identity.equals(connection.getSubCode()))
                .map(connection -> new ConnectionMatch(connection.getId(), displayName(connection),
                        boundIds.contains(connection.getId())))
                .toList();
    }

    /** The skill's own caption, else the key where it says more than the code, else the catalogue name. */
    private static String title(ConnectorRequirement requirement, Connector connector) {
        if (requirement.title() != null) {
            return requirement.title();
        }
        if (!requirement.key().equals(requirement.code())) {
            return requirement.key();
        }
        return connector != null && connector.getName() != null ? connector.getName() : requirement.code();
    }

    private static Map<String, CredentialFieldResponse> credentialFields(IntegrationConnectorHandler handler) {
        Map<String, CredentialFieldResponse> fields = new LinkedHashMap<>();
        handler.getCredentialFields().forEach((code, field) -> fields.put(code, CredentialFieldResponse.from(field)));
        return fields;
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
