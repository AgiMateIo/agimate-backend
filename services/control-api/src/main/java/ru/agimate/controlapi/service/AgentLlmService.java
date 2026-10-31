package ru.agimate.controlapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.ConflictStatusException;
import ru.agimate.common.rest.error.ForbiddenStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.controlapi.controller.agent.dto.AgentLlmRuntimeResponse;
import ru.agimate.controlapi.controller.manage.dto.llm.AgentLlmResponse;
import ru.agimate.controlapi.controller.manage.dto.llm.CreateAgentLlmRequest;
import ru.agimate.controlapi.controller.manage.dto.llm.UpdateAgentLlmRequest;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.entities.AgentLlm;
import ru.agimate.controlapi.database.entities.LlmProvider;
import ru.agimate.controlapi.database.entities.PlatformLlm;
import ru.agimate.controlapi.database.enums.AgentType;
import ru.agimate.controlapi.database.enums.LlmPurpose;
import ru.agimate.controlapi.database.enums.LlmSource;
import ru.agimate.controlapi.database.repositories.AgentLlmRepository;
import ru.agimate.controlapi.database.repositories.AgentRepository;
import ru.agimate.controlapi.database.repositories.LlmProviderRepository;
import ru.agimate.controlapi.database.repositories.PlatformLlmRepository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The CRUD of {@code agent_llms}: a purpose holds an ordered chain of bindings, and the resolver takes
 * the first eligible one (docs/decisions/agent-llm-purpose-chain.md). The order is set by a human —
 * {@link #create} appends, {@link #replaceChain} rewrites the whole chain.
 *
 * <p>The agent's source decides whether the rows are effective
 * (docs/decisions/platform-llm-purposes-and-agent-source.md): a {@code PLATFORM} agent keeps its
 * bindings but lists the platform's effective configuration beside them as inactive; a {@code BYOK}
 * agent uses the rows alone. The manage endpoints refuse to add or replace bindings on a PLATFORM
 * agent — the explicit switch changes the payer — while deletion and {@code PUT []} stay allowed as
 * cleanup. The platform connector's {@link #setAgentLlm} is the one exception: a non-empty chain
 * switches the agent to BYOK atomically, so the meta-agent can finish a BYOK setup in one call.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AgentLlmService {

    /**
     * Ceiling on a purpose's chain: resolution runs on every llm call and every candidate costs a
     * registry read and a quota query. Beyond five it is a profile, not a fallback path.
     */
    public static final int MAX_BINDINGS_PER_PURPOSE = 5;

    private final AgentLlmRepository agentLlmRepository;
    private final PlatformLlmRepository platformLlmRepository;
    private final AgentRepository agentRepository;
    private final LlmProviderRepository llmProviderRepository;
    private final LlmProviderService llmProviderService;

    /** One link of a chain, as the platform connector passes it (no {@code controller/**} dependency). */
    public record Binding(UUID llmProviderId, String model) {
    }

    /**
     * Purposes in the declaration order of {@link LlmPurpose}, then priority. The column is {@code TEXT},
     * so the database order would be alphabetical; the enum's order is the one a human reads.
     */
    private static final Comparator<AgentLlm> BY_PURPOSE_THEN_PRIORITY =
            Comparator.comparing(AgentLlm::getPurpose).thenComparingInt(AgentLlm::getPriority);

    public List<AgentLlmResponse> listForAgent(UUID agentId, UUID userId) {
        Agent agent = requireOwnedAgent(agentId, userId);
        List<AgentLlm> bindings = agentLlmRepository
                .findAllByAgentIdOrderByPurposeAscPriorityAsc(agent.getId());
        Map<UUID, LlmProvider> providersById = loadProviders(bindings);
        List<PlatformLlm> platformRows = agent.getLlmSource() == LlmSource.PLATFORM
                ? platformLlmRepository.findAllByOrderByPurposeAscPriorityAsc() : List.of();
        return effectiveBindings(agent, bindings, providersById, platformRows,
                loadPlatformProviders(platformRows));
    }

    public Map<UUID, List<AgentLlmResponse>> listForAgents(List<Agent> agents) {
        if (agents.isEmpty()) {
            return Map.of();
        }
        List<UUID> agentIds = agents.stream().map(Agent::getId).toList();
        List<AgentLlm> bindings =
                agentLlmRepository.findAllByAgentIdInOrderByAgentIdAscPurposeAscPriorityAsc(agentIds);
        Map<UUID, LlmProvider> providersById = loadProviders(bindings);
        Map<UUID, List<AgentLlm>> bindingsByAgent = bindings.stream()
                .collect(Collectors.groupingBy(AgentLlm::getAgentId));

        List<PlatformLlm> platformRows = agents.stream()
                .anyMatch(a -> a.getLlmSource() == LlmSource.PLATFORM)
                ? platformLlmRepository.findAllByOrderByPurposeAscPriorityAsc()
                : List.of();
        Map<UUID, LlmProvider> platformProviders = loadPlatformProviders(platformRows);

        Map<UUID, List<AgentLlmResponse>> result = new HashMap<>();
        for (Agent agent : agents) {
            result.put(agent.getId(), effectiveBindings(agent,
                    bindingsByAgent.getOrDefault(agent.getId(), List.of()),
                    providersById, platformRows, platformProviders));
        }
        return result;
    }

    /**
     * The listing of an agent: the effective platform configuration for a PLATFORM agent (all
     * purposes for {@code GENERIC}, media only for an external brain — it has no agent loop to serve
     * CHAT or ROUTINE), then its own inactive rows with {@code active = false}. A BYOK agent lists its
     * own rows alone; all of them are active.
     */
    private List<AgentLlmResponse> effectiveBindings(Agent agent, List<AgentLlm> bindings,
                                                     Map<UUID, LlmProvider> providersById,
                                                     List<PlatformLlm> platformRows,
                                                     Map<UUID, LlmProvider> platformProviders) {
        List<AgentLlmResponse> result = new ArrayList<>();
        boolean platform = agent.getLlmSource() == LlmSource.PLATFORM;
        if (platform) {
            boolean generic = agent.getType() == AgentType.GENERIC;
            for (PlatformLlm row : platformRows) {
                if (!generic && !row.getPurpose().isMedia()) {
                    continue;
                }
                result.add(AgentLlmResponse.platform(row, platformProviders.get(row.getLlmProviderId())));
            }
        }
        for (AgentLlm binding : bindings) {
            result.add(AgentLlmResponse.from(binding,
                    providersById.get(binding.getLlmProviderId()), !platform));
        }
        result.sort(Comparator.comparing(AgentLlmResponse::purpose)
                .thenComparing(r -> r.source() == AgentLlmResponse.Source.PLATFORM ? 0 : 1)
                .thenComparingInt(AgentLlmResponse::priority));
        return result;
    }

    @Transactional
    public AgentLlmResponse create(UUID agentId, UUID userId, CreateAgentLlmRequest request) {
        LlmPurpose purpose = request.purpose() != null ? request.purpose() : LlmPurpose.CHAT;
        AgentLlm binding = append(agentId, userId, request.llmProviderId(), request.model(), purpose);
        return AgentLlmResponse.from(binding,
                llmProviderService.requireOwned(request.llmProviderId(), userId), true);
    }

    @Transactional
    public AgentLlm create(UUID agentId, UUID userId, UUID llmProviderId, String model, LlmPurpose purpose) {
        return append(agentId, userId, llmProviderId, model,
                purpose != null ? purpose : LlmPurpose.CHAT);
    }

    private AgentLlm append(UUID agentId, UUID userId, UUID llmProviderId, String model, LlmPurpose purpose) {
        Agent agent = requireOwnedAgent(agentId, userId);
        requireByokForManage(agent);
        LlmProvider provider = llmProviderService.requireOwned(llmProviderId, userId);
        validateModel(provider, model);

        List<AgentLlm> chain =
                agentLlmRepository.findAllByAgentIdAndPurposeOrderByPriorityAsc(agent.getId(), purpose);
        if (chain.size() >= MAX_BINDINGS_PER_PURPOSE) {
            throw new ConflictStatusException("The " + purpose + " chain of this agent is full ("
                    + MAX_BINDINGS_PER_PURPOSE + " bindings); remove one before adding another");
        }
        if (chain.stream().anyMatch(b -> sameBinding(b, provider.getId(), model))) {
            throw new ConflictStatusException("The " + purpose + " chain already binds model '" + model
                    + "' of provider '" + provider.getName() + "'");
        }

        int priority = chain.stream().mapToInt(AgentLlm::getPriority).max().orElse(-1) + 1;
        AgentLlm binding = agentLlmRepository.save(AgentLlm.builder()
                .userId(userId)
                .agentId(agent.getId())
                .llmProviderId(provider.getId())
                .model(model)
                .purpose(purpose)
                .priority(priority)
                .build());
        try {
            // Flush now: two POSTs compute the same max + 1, and the loser must get a conflict, not
            // a persistence error thrown from the commit after the method has returned.
            agentLlmRepository.flush();
        } catch (DataIntegrityViolationException e) {
            log.warn("Lost the agent_llm append race: agent={} purpose={}", agent.getId(), purpose, e);
            throw new ConflictStatusException("LLM bindings changed concurrently — retry");
        }

        log.info("Appended agent_llm: agent={} purpose={} provider={} model={} priority={}",
                agent.getId(), purpose, provider.getId(), binding.getModel(), priority);
        return binding;
    }

    @Transactional
    public List<AgentLlmResponse> replaceChain(UUID agentId, UUID userId, LlmPurpose purpose,
                                               UpdateAgentLlmRequest request) {
        List<Binding> bindings = request.bindings().stream()
                .map(b -> new Binding(b.llmProviderId(), b.model()))
                .toList();
        List<AgentLlm> chain = replaceChain(agentId, userId, purpose, bindings);
        Map<UUID, LlmProvider> providersById = loadProviders(chain);
        return chain.stream()
                .map(b -> AgentLlmResponse.from(b, providersById.get(b.getLlmProviderId()), true))
                .toList();
    }

    /**
     * Rewrites the purpose's chain: the old rows are deleted and the list is inserted with
     * {@code priority} taken from the position. A delete-then-insert in one transaction, because the
     * unique {@code (agent_id, purpose, priority)} does not survive an in-place swap.
     *
     * <p>A PLATFORM agent refuses a non-empty chain — the payer changes only through the explicit
     * switch — but {@code []} is cleanup and stays allowed.
     *
     * @param bindings the chain in the wanted order; empty removes the purpose
     */
    @Transactional
    public List<AgentLlm> replaceChain(UUID agentId, UUID userId, LlmPurpose purpose,
                                       List<Binding> bindings) {
        Agent agent = requireOwnedAgent(agentId, userId);
        if (agent.getLlmSource() == LlmSource.PLATFORM && !bindings.isEmpty()) {
            throw platformSourceRefusal();
        }
        return doReplaceChain(agent, userId, purpose, bindings);
    }

    /**
     * The platform connector's entry point. A non-empty chain on a PLATFORM agent switches the agent
     * to BYOK in the same transaction that writes the chain — one call finishes a BYOK setup, and a
     * failed chain write rolls the switch back. An empty list is cleanup and never changes the source.
     */
    @Transactional
    public List<AgentLlm> setAgentLlm(UUID agentId, UUID userId, LlmPurpose purpose,
                                      List<Binding> bindings) {
        Agent agent = requireOwnedAgent(agentId, userId);
        if (!bindings.isEmpty() && agent.getLlmSource() != LlmSource.BYOK) {
            agent.setLlmSource(LlmSource.BYOK);
            agentRepository.save(agent);
            log.info("Switched agent to BYOK via set_agent_llm: agent={}", agent.getId());
        }
        return doReplaceChain(agent, userId, purpose, bindings);
    }

    private List<AgentLlm> doReplaceChain(Agent agent, UUID userId, LlmPurpose purpose,
                                          List<Binding> bindings) {
        if (bindings.size() > MAX_BINDINGS_PER_PURPOSE) {
            throw new ConflictStatusException("A purpose chain holds at most "
                    + MAX_BINDINGS_PER_PURPOSE + " bindings, got " + bindings.size());
        }

        Set<String> seen = new HashSet<>();
        List<AgentLlm> rows = new ArrayList<>(bindings.size());
        int priority = 0;
        for (Binding binding : bindings) {
            LlmProvider provider = llmProviderService.requireOwned(binding.llmProviderId(), userId);
            String model = binding.model();
            if (model == null || model.isBlank()) {
                throw new BadRequestStatusException("Model is required for every binding");
            }
            validateModel(provider, model);
            if (!seen.add(provider.getId() + "\u0000" + model)) {
                throw new BadRequestStatusException("Duplicate binding of model '" + model
                        + "' of provider '" + provider.getName() + "' in one " + purpose + " chain");
            }
            rows.add(AgentLlm.builder()
                    .userId(userId)
                    .agentId(agent.getId())
                    .llmProviderId(provider.getId())
                    .model(model)
                    .purpose(purpose)
                    .priority(priority++)
                    .build());
        }

        agentLlmRepository.deleteAllInBatch(
                agentLlmRepository.findAllByAgentIdAndPurposeOrderByPriorityAsc(agent.getId(), purpose));
        List<AgentLlm> saved;
        try {
            // A concurrent POST appending to the same purpose computes the same priority; the loser
            // must get a conflict, not a persistence error thrown from the commit.
            saved = agentLlmRepository.saveAll(rows);
            agentLlmRepository.flush();
        } catch (DataIntegrityViolationException e) {
            log.warn("Lost the agent_llm replace race: agent={} purpose={}", agent.getId(), purpose, e);
            throw new ConflictStatusException("LLM bindings changed concurrently — retry");
        }

        log.info("Replaced agent_llm chain: agent={} purpose={} bindings={}",
                agent.getId(), purpose, saved.size());
        return saved;
    }

    @Transactional
    public void delete(UUID agentId, UUID userId, LlmPurpose purpose) {
        Agent agent = requireOwnedAgent(agentId, userId);
        List<AgentLlm> chain =
                agentLlmRepository.findAllByAgentIdAndPurposeOrderByPriorityAsc(agent.getId(), purpose);
        if (chain.isEmpty()) {
            throw new NotFoundStatusException("No LLM binding for purpose " + purpose + " on this agent");
        }
        agentLlmRepository.deleteAllInBatch(chain);
        log.info("Deleted agent_llm chain: agent={} purpose={} bindings={}",
                agent.getId(), purpose, chain.size());
    }

    /** The external listing: only active BYOK bindings; a PLATFORM agent exposes nothing. */
    public List<AgentLlmRuntimeResponse> runtimeForAgent(UUID agentId) {
        Agent agent = requireAgent(agentId);
        if (agent.getLlmSource() == LlmSource.PLATFORM) {
            return List.of();
        }
        List<AgentLlm> bindings = agentLlmRepository.findAllByAgentIdOrderByPurposeAscPriorityAsc(agentId)
                .stream().sorted(BY_PURPOSE_THEN_PRIORITY).toList();
        Map<UUID, LlmProvider> providersById = loadProviders(bindings);
        return bindings.stream()
                .map(b -> toRuntime(b, providersById.get(b.getLlmProviderId())))
                .filter(Objects::nonNull)
                .toList();
    }

    /** The first active binding of the purpose whose provider is enabled; a PLATFORM agent is a 404. */
    public AgentLlmRuntimeResponse runtimeForAgentByPurpose(UUID agentId, LlmPurpose purpose) {
        Agent agent = requireAgent(agentId);
        if (agent.getLlmSource() == LlmSource.PLATFORM) {
            throw new NotFoundStatusException(
                    "The agent runs on the platform LLM source; no own binding is exposed");
        }
        List<AgentLlm> chain =
                agentLlmRepository.findAllByAgentIdAndPurposeOrderByPriorityAsc(agentId, purpose);
        if (chain.isEmpty()) {
            throw new NotFoundStatusException("No LLM binding for purpose " + purpose + " on this agent");
        }
        Map<UUID, LlmProvider> providersById = loadProviders(chain);
        return chain.stream()
                .map(b -> toRuntime(b, providersById.get(b.getLlmProviderId())))
                .filter(Objects::nonNull)
                .findFirst()
                .orElseThrow(() -> new NotFoundStatusException(
                        "LLM provider for purpose " + purpose + " is disabled"));
    }

    private static boolean sameBinding(AgentLlm binding, UUID providerId, String model) {
        return binding.getLlmProviderId().equals(providerId) && binding.getModel().equals(model);
    }

    private AgentLlmRuntimeResponse toRuntime(AgentLlm binding, LlmProvider provider) {
        if (provider == null || !provider.isEnabled()) {
            return null;
        }
        return new AgentLlmRuntimeResponse(
                binding.getPurpose(),
                provider.getProviderType(),
                provider.getBaseUrl(),
                binding.getModel(),
                binding.getPriority()
        );
    }

    private Map<UUID, LlmProvider> loadProviders(List<AgentLlm> bindings) {
        return loadProvidersByIds(bindings.stream().map(AgentLlm::getLlmProviderId).toList());
    }

    private Map<UUID, LlmProvider> loadPlatformProviders(List<PlatformLlm> bindings) {
        return loadProvidersByIds(bindings.stream().map(PlatformLlm::getLlmProviderId).toList());
    }

    private Map<UUID, LlmProvider> loadProvidersByIds(List<UUID> providerIds) {
        List<UUID> ids = providerIds.stream().distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return llmProviderRepository.findAllByIdIn(ids).stream()
                .collect(Collectors.toMap(LlmProvider::getId, Function.identity()));
    }

    private Agent requireOwnedAgent(UUID agentId, UUID userId) {
        Agent agent = requireAgent(agentId);
        if (!agent.getUserId().equals(userId)) {
            throw new ForbiddenStatusException("Access denied");
        }
        return agent;
    }

    private Agent requireAgent(UUID agentId) {
        return agentRepository.findById(agentId)
                .orElseThrow(() -> new NotFoundStatusException("Agent not found"));
    }

    private static void requireByokForManage(Agent agent) {
        if (agent.getLlmSource() == LlmSource.PLATFORM) {
            throw platformSourceRefusal();
        }
    }

    private static BadRequestStatusException platformSourceRefusal() {
        return new BadRequestStatusException("The agent runs on the platform models; "
                + "switch its LLM source to BYOK first");
    }

    private void validateModel(LlmProvider provider, String model) {
        llmProviderService.validateModelsKnown(provider, List.of(model));
    }
}
