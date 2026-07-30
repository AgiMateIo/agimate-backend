package ru.agimate.controlapi.service.llm.catalog;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import ru.agimate.controlapi.database.entities.LlmProviderCatalogEntry;
import ru.agimate.controlapi.database.repositories.LlmProviderCatalogRepository;
import ru.agimate.controlapi.service.seed.LlmCatalogTexts;

import java.util.List;

/**
 * Seeding of the LLM provider catalogue at application start: an upsert by {@code code} from
 * {@code seed/llm-providers.yaml}, modelled on {@code ConnectorBootstrap} rather than on
 * {@code SystemPresetBootstrap}.
 *
 * <p>The choice between the two is the whole design of this table. Seed-only-if-missing would freeze
 * the catalogue at whatever the installation first saw — and its most perishable content is model
 * ids, exactly what needs to arrive with a deploy. So the seed rewrites the content, and the single
 * field an installation may own — {@code enabled} — is never written back. A code absent from the
 * file is not touched: this loop walks the file, not the table, so a hand-added corporate gateway
 * survives.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmProviderCatalogBootstrap {

    private final LlmProviderCatalogRepository catalogRepository;
    private final LlmCatalogTexts catalogTexts;

    @EventListener(ApplicationReadyEvent.class)
    public void bootstrap() {
        // A malformed seed throws out of here and fails the start — it ships inside the jar, so it can
        // only be our own bad edit, and the build already checks it.
        List<LlmCatalogSeedEntry> entries = LlmCatalogSeed.load();

        // No enclosing transaction — as in the other bootstraps: a unique-code conflict on one entry
        // (two nodes racing on a cold start) must not poison the rest.
        int seeded = 0;
        for (LlmCatalogSeedEntry entry : entries) {
            try {
                upsert(entry);
                seeded++;
            } catch (Exception e) {
                log.error("Failed to seed LLM provider catalogue entry {}: {}", entry.code(), e.getMessage());
            }
        }
        log.info("LLM provider catalogue bootstrapped: {}/{} entries", seeded, entries.size());
    }

    private void upsert(LlmCatalogSeedEntry entry) {
        LlmProviderCatalogEntry row = catalogRepository.findByCode(entry.code())
                .orElseGet(() -> LlmProviderCatalogEntry.builder()
                        .code(entry.code())
                        .build());

        row.setName(entry.name());
        row.setDescription(catalogTexts.description(entry.code(), entry.description()));
        row.setProviderType(entry.providerType());
        row.setBaseUrl(entry.baseUrl());
        row.setMediaTransport(entry.mediaTransport());
        row.setPurposePriority(entry.purposePriority());
        row.setApiKeyUrl(entry.apiKeyUrl());
        row.setSortOrder(entry.sortOrder());
        // enabled is left alone on purpose: a recommendation switched off on this installation stays
        // off, while its content keeps being updated.

        catalogRepository.save(row);
    }
}
