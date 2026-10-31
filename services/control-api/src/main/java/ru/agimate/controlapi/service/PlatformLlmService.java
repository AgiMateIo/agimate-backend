package ru.agimate.controlapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.ConflictStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.controlapi.controller.manage.dto.llm.PlatformLlmResponse;
import ru.agimate.controlapi.database.entities.LlmProvider;
import ru.agimate.controlapi.database.entities.PlatformLlm;
import ru.agimate.controlapi.database.enums.LlmPurpose;
import ru.agimate.controlapi.database.repositories.LlmProviderRepository;
import ru.agimate.controlapi.database.repositories.PlatformLlmRepository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The CRUD of {@code platform_llms}: the platform's ordered chains, one per purpose, mirroring
 * {@link AgentLlmService}. There is no append — platform configuration is rare and a whole-chain
 * {@code PUT} covers the scenario (docs/decisions/platform-llm-purposes-and-agent-source.md).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PlatformLlmService {

    /** The same ceiling as an agent's chain: resolution walks every candidate on each llm call. */
    public static final int MAX_BINDINGS_PER_PURPOSE = 5;

    private final PlatformLlmRepository platformLlmRepository;
    private final LlmProviderRepository llmProviderRepository;
    private final LlmProviderService llmProviderService;

    /** One link of a chain; the admin controller and the resolver both speak this shape. */
    public record Binding(UUID llmProviderId, String model) {
    }

    private static final Comparator<PlatformLlm> BY_PURPOSE_THEN_PRIORITY =
            Comparator.comparing(PlatformLlm::getPurpose).thenComparingInt(PlatformLlm::getPriority);

    public List<PlatformLlmResponse> list() {
        List<PlatformLlm> bindings = platformLlmRepository.findAllByOrderByPurposeAscPriorityAsc()
                .stream().sorted(BY_PURPOSE_THEN_PRIORITY).toList();
        Map<UUID, LlmProvider> providersById = loadProviders(bindings);
        return bindings.stream()
                .map(b -> PlatformLlmResponse.from(b, providersById.get(b.getLlmProviderId())))
                .toList();
    }

    /**
     * Rewrites the purpose's chain: the old rows are deleted and the list is inserted with
     * {@code priority} taken from the position — the same delete-then-insert as an agent's chain,
     * because the unique {@code (purpose, priority)} does not survive an in-place swap.
     */
    @Transactional
    public List<PlatformLlmResponse> replaceChain(LlmPurpose purpose, List<Binding> bindings) {
        if (bindings.size() > MAX_BINDINGS_PER_PURPOSE) {
            throw new ConflictStatusException("A platform purpose holds at most "
                    + MAX_BINDINGS_PER_PURPOSE + " bindings, got " + bindings.size());
        }

        Set<String> seen = new HashSet<>();
        List<PlatformLlm> rows = new ArrayList<>(bindings.size());
        Map<UUID, LlmProvider> providersById = new HashMap<>();
        int priority = 0;
        for (Binding binding : bindings) {
            LlmProvider provider = llmProviderRepository.findById(binding.llmProviderId())
                    .orElseThrow(() -> new NotFoundStatusException("LLM provider not found"));
            if (!LlmProviderService.isPlatform(provider)) {
                throw new BadRequestStatusException(
                        "Platform purposes can only bind platform providers");
            }
            String model = binding.model();
            if (model == null || model.isBlank()) {
                throw new BadRequestStatusException("Model is required for every binding");
            }
            llmProviderService.validateModelsKnown(provider, List.of(model));
            if (!seen.add(provider.getId() + "\u0000" + model)) {
                throw new BadRequestStatusException("Duplicate binding of model '" + model
                        + "' of provider '" + provider.getName() + "' in one " + purpose + " chain");
            }
            rows.add(PlatformLlm.builder()
                    .llmProviderId(provider.getId())
                    .model(model)
                    .purpose(purpose)
                    .priority(priority++)
                    .build());
            providersById.put(provider.getId(), provider);
        }

        platformLlmRepository.deleteAllInBatch(
                platformLlmRepository.findAllByPurposeOrderByPriorityAsc(purpose));
        List<PlatformLlm> saved;
        try {
            saved = platformLlmRepository.saveAll(rows);
            platformLlmRepository.flush();
        } catch (DataIntegrityViolationException e) {
            log.warn("Lost the platform_llm replace race: purpose={}", purpose, e);
            throw new ConflictStatusException("Platform LLM bindings changed concurrently — retry");
        }

        log.info("Replaced platform_llm chain: purpose={} bindings={}", purpose, saved.size());
        return saved.stream()
                .map(b -> PlatformLlmResponse.from(b, providersById.get(b.getLlmProviderId())))
                .toList();
    }

    @Transactional
    public void delete(LlmPurpose purpose) {
        List<PlatformLlm> chain =
                platformLlmRepository.findAllByPurposeOrderByPriorityAsc(purpose);
        if (chain.isEmpty()) {
            throw new NotFoundStatusException("No platform LLM binding for purpose " + purpose);
        }
        platformLlmRepository.deleteAllInBatch(chain);
        log.info("Deleted platform_llm chain: purpose={} bindings={}", purpose, chain.size());
    }

    private Map<UUID, LlmProvider> loadProviders(List<PlatformLlm> bindings) {
        if (bindings.isEmpty()) {
            return Map.of();
        }
        List<UUID> ids = bindings.stream().map(PlatformLlm::getLlmProviderId).distinct().toList();
        return llmProviderRepository.findAllByIdIn(ids).stream()
                .collect(Collectors.toMap(LlmProvider::getId, Function.identity()));
    }
}
