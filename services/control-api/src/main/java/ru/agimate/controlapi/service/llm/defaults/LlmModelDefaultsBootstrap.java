package ru.agimate.controlapi.service.llm.defaults;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import ru.agimate.controlapi.database.entities.LlmModelDefaults;
import ru.agimate.controlapi.database.repositories.LlmModelDefaultsRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Seeding of the curated capability fallback ({@code llm_model_defaults}) from
 * {@code seed/llm-models.yaml} at application start.
 *
 * <p>The migration only creates the table and never fills it. Seeding rows from the changelog would
 * mean a refreshed snapshot never reaches an installation that had already been seeded — the data
 * would age in place and only a data migration could move it. Here the file owns the content:
 * refreshing the snapshot is a deploy. Overwriting is safe because nothing writes this table at
 * runtime and the fallback is applied per field — a value discovered from the provider always wins,
 * so a changed row cannot break a working provider's registry.
 *
 * <p>Rows are matched by {@code model}; a row absent from the file is left alone, so an entry added
 * by hand for a model we do not ship survives.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmModelDefaultsBootstrap {

    private final LlmModelDefaultsRepository modelDefaultsRepository;

    @EventListener(ApplicationReadyEvent.class)
    public void bootstrap() {
        List<LlmModelDefaultsSeedEntry> seed = LlmModelDefaultsSeed.load();

        // One read instead of a lookup per model: the snapshot is a few hundred rows, and this runs on
        // every start.
        Map<String, LlmModelDefaults> existing = modelDefaultsRepository.findAll().stream()
                .collect(Collectors.toMap(LlmModelDefaults::getModel, Function.identity(), (a, b) -> a));

        List<LlmModelDefaults> changed = new ArrayList<>();
        for (LlmModelDefaultsSeedEntry entry : seed) {
            LlmModelDefaults row = existing.get(entry.model());
            if (row == null) {
                changed.add(apply(LlmModelDefaults.builder().model(entry.model()).build(), entry));
            } else if (differs(row, entry)) {
                // Untouched rows are not saved: an UPDATE per model on every start would make
                // updated_at meaningless and write a few hundred rows for nothing.
                changed.add(apply(row, entry));
            }
        }
        modelDefaultsRepository.saveAll(changed);

        log.info("LLM model defaults bootstrapped: {} seeded, {} written", seed.size(), changed.size());
    }

    private static LlmModelDefaults apply(LlmModelDefaults row, LlmModelDefaultsSeedEntry entry) {
        row.setDisplayName(entry.displayName());
        row.setContextWindow(entry.contextWindow());
        row.setMaxOutputTokens(entry.maxOutputTokens());
        row.setInputModalities(entry.inputModalities());
        row.setOutputModalities(entry.outputModalities());
        row.setSupportedParameters(entry.supportedParameters());
        return row;
    }

    private static boolean differs(LlmModelDefaults row, LlmModelDefaultsSeedEntry entry) {
        return !Objects.equals(row.getDisplayName(), entry.displayName())
                || !Objects.equals(row.getContextWindow(), entry.contextWindow())
                || !Objects.equals(row.getMaxOutputTokens(), entry.maxOutputTokens())
                || !Objects.equals(row.getInputModalities(), entry.inputModalities())
                || !Objects.equals(row.getOutputModalities(), entry.outputModalities())
                || !Objects.equals(row.getSupportedParameters(), entry.supportedParameters());
    }
}
