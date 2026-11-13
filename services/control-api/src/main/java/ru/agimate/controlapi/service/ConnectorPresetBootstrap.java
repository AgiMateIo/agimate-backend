package ru.agimate.controlapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.controlapi.connectors.core.ConnectorBootstrap;
import ru.agimate.controlapi.connectors.core.ConnectorRegistry;
import ru.agimate.controlapi.connectors.core.dto.CredentialField;
import ru.agimate.controlapi.database.entities.ConnectorPreset;
import ru.agimate.controlapi.database.entities.ConnectorPresetText;
import ru.agimate.controlapi.database.enums.ContentLanguage;
import ru.agimate.controlapi.database.repositories.ConnectorPresetRepository;
import ru.agimate.controlapi.database.repositories.ConnectorPresetTextRepository;
import ru.agimate.controlapi.service.seed.ConnectorPresetSeed;
import ru.agimate.controlapi.service.seed.ConnectorPresetSeedReader;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Seeding of connector presets at application start — after the connector catalogue ({@link
 * ConnectorBootstrap}) so the {@code connector_code} foreign key already resolves.
 *
 * <p>The JSON files are the source of truth; this class is an idempotent projection. Two phases: hide
 * every existing row ({@code source_present = false}), then upsert by {@code code}. A row whose file
 * disappeared stays hidden; a row whose file returned is visible again. {@code enabled} is the
 * installation's switch and is never written here.
 *
 * <p>An empty seed directory is not an instruction to hide the catalogue: a deployment without copied
 * files keeps what it has and logs a warning.
 *
 * <p>One transaction, unlike the seed-only-if-missing content bootstraps: a half-applied hide/upsert
 * would leave the catalogue empty, and the seed is our own jar resource, so a bad edit must fail
 * startup rather than leave a partial projection behind. A transaction-scoped advisory lock
 * serialises the seed across replicas, so a cold start does not race the unique code index.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConnectorPresetBootstrap {

    /** After connectors, skills and the agent presets. */
    public static final int BOOTSTRAP_ORDER = ConnectorBootstrap.BOOTSTRAP_ORDER + 3;

    private final ConnectorPresetSeedReader reader;
    private final ConnectorPresetRepository connectorPresetRepository;
    private final ConnectorPresetTextRepository connectorPresetTextRepository;
    private final ConnectorRegistry connectorRegistry;

    @Transactional
    @Order(BOOTSTRAP_ORDER)
    @EventListener(ApplicationReadyEvent.class)
    public void bootstrap() {
        List<ConnectorPresetSeed> seeds = reader.readAll();
        if (seeds.isEmpty()) {
            return;
        }

        seeds.forEach(this::validateAgainstConnector);
        // Two replicas on a cold start would both insert and one would fail the unique code index.
        connectorPresetRepository.lockSeed();
        int hiddenRows = connectorPresetRepository.hideAll(LocalDateTime.now());
        int created = 0;
        int updated = 0;
        int translations = 0;
        for (ConnectorPresetSeed seed : seeds) {
            ConnectorPreset preset = connectorPresetRepository.findByCode(seed.code())
                    .orElseGet(() -> ConnectorPreset.builder().code(seed.code()).build());
            boolean isNew = preset.getId() == null;
            apply(seed, preset);
            preset = connectorPresetRepository.save(preset);
            if (isNew) {
                created++;
            } else {
                updated++;
            }

            // The seed owns the translations wholesale; deleting first also removes a stale RU row.
            connectorPresetTextRepository.deleteByPresetId(preset.getId());
            if (seed.nameRu() != null || seed.descriptionRu() != null) {
                connectorPresetTextRepository.save(ConnectorPresetText.builder()
                        .presetId(preset.getId())
                        .language(ContentLanguage.RU)
                        .name(seed.nameRu())
                        .description(seed.descriptionRu())
                        .build());
                translations++;
            }
        }
        log.info("Connector presets bootstrapped: files={}, created={}, updated={}, translations={}, hidden={}",
                seeds.size(), created, updated, translations, Math.max(0, hiddenRows - updated));
    }

    /**
     * A preset may override or prefill only fields the connector declares. A typo in a field code
     * would otherwise sit in JSONB and never match the form assembler — a silent no-op. The check runs
     * before the hide phase, so a bad seed does not empty the catalogue on the way to failing.
     */
    private void validateAgainstConnector(ConnectorPresetSeed seed) {
        connectorRegistry.findIntegrationHandler(seed.connectorCode()).ifPresent(handler -> {
            Map<String, CredentialField> declared = handler.getCredentialFields();
            if (seed.fields() != null) {
                for (String code : seed.fields().keySet()) {
                    if (!declared.containsKey(code)) {
                        throw new IllegalStateException("Preset '" + seed.code()
                                + "' overrides unknown credential field '" + code
                                + "' of connector '" + seed.connectorCode() + "'");
                    }
                }
            }
            for (String code : seed.params().keySet()) {
                CredentialField field = declared.get(code);
                if (field == null) {
                    throw new IllegalStateException("Preset '" + seed.code()
                            + "' has unknown param '" + code + "' of connector '" + seed.connectorCode() + "'");
                }
                if (field.type() == CredentialField.Type.SECRET) {
                    throw new IllegalStateException("Preset '" + seed.code()
                            + "' stores SECRET field '" + code + "' in open params");
                }
            }
        });
    }

    private static void apply(ConnectorPresetSeed seed, ConnectorPreset preset) {
        preset.setCode(seed.code());
        preset.setConnectorCode(seed.connectorCode());
        preset.setName(seed.name());
        preset.setDescription(seed.description());
        preset.setIconUrl(seed.iconUrl());
        preset.setCategory(seed.category());
        preset.setJurisdiction(seed.jurisdiction());
        preset.setAuthMethods(new ArrayList<>(seed.authMethods()));
        preset.setAvailability(seed.availability());
        preset.setDocsUrl(seed.docsUrl());
        preset.setParams(new LinkedHashMap<>(seed.params()));
        preset.setIdentity(seed.identity());
        preset.setFields(seed.fields());
        preset.setHeaderFields(seed.headerFields());
        preset.setHeaderGroups(seed.headerGroups());
        preset.setSourcePresent(true);
    }
}
