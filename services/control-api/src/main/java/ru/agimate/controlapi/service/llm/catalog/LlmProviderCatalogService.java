package ru.agimate.controlapi.service.llm.catalog;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.controlapi.controller.manage.dto.llm.LlmProviderCatalogResponse;
import ru.agimate.controlapi.database.repositories.LlmProviderCatalogRepository;

import java.util.List;

/**
 * Read side of the provider catalogue: what the «add a provider» form offers to prefill with.
 * Entries switched off on this installation are not listed at all — the flag is an editorial
 * decision, not a state the user has to reason about.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LlmProviderCatalogService {

    private final LlmProviderCatalogRepository catalogRepository;

    public List<LlmProviderCatalogResponse> list() {
        return catalogRepository.findByEnabledTrueOrderBySortOrderAscNameAsc().stream()
                .map(LlmProviderCatalogResponse::from)
                .toList();
    }
}
