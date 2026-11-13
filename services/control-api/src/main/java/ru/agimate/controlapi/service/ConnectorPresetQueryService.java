package ru.agimate.controlapi.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.controlapi.controller.catalog.dto.CatalogConnectorPresetResponse;
import ru.agimate.controlapi.controller.manage.dto.ConnectorPresetCard;
import ru.agimate.controlapi.controller.manage.dto.ConnectorPresetConnection;
import ru.agimate.controlapi.controller.manage.dto.ConnectorPresetResponse;
import ru.agimate.controlapi.database.entities.ConnectorPreset;
import ru.agimate.controlapi.database.entities.ConnectorPresetText;
import ru.agimate.controlapi.database.enums.ConnectorPresetAuth;
import ru.agimate.controlapi.database.enums.ContentCategory;
import ru.agimate.controlapi.database.enums.ContentLanguage;
import ru.agimate.controlapi.database.repositories.ConnectorPresetRepository;
import ru.agimate.controlapi.database.repositories.ConnectorPresetTextRepository;
import ru.agimate.controlapi.service.seed.ContentLanguageResolver;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The read side of the connector catalogue. The same query and the same card serve both audiences:
 * {@code /manage} adds the form, {@code /public/catalog} must not.
 *
 * <p>Search runs in SQL over both languages; the localized name is chosen here and used for the
 * ordering. The list is a few dozen rows, so a post-query sort is cheaper than a second join.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ConnectorPresetQueryService {

    private final ConnectorPresetRepository connectorPresetRepository;
    private final ConnectorPresetTextRepository connectorPresetTextRepository;
    private final ContentLanguageResolver contentLanguageResolver;
    private final PresetFormAssembler presetFormAssembler;

    public List<ConnectorPresetResponse> list(String search, String connectorCode,
                                              ContentCategory category, String jurisdiction) {
        ContentLanguage language = contentLanguageResolver.requestLanguage();
        List<ConnectorPreset> presets = search(search, connectorCode, category, jurisdiction);
        Map<UUID, ConnectorPresetText> texts = texts(presets, language);
        return presets.stream()
                .sorted(Comparator.comparing(preset -> name(preset, texts.get(preset.getId())),
                        String.CASE_INSENSITIVE_ORDER))
                .map(preset -> toManage(preset, texts.get(preset.getId()), language))
                .toList();
    }

    public ConnectorPresetResponse get(String code) {
        ContentLanguage language = contentLanguageResolver.requestLanguage();
        ConnectorPreset preset = requirePreset(code);
        ConnectorPresetText text = connectorPresetTextRepository
                .findByPresetIdAndLanguage(preset.getId(), language)
                .orElse(null);
        return toManage(preset, text, language);
    }

    public List<CatalogConnectorPresetResponse> listPublic(String search, ContentCategory category,
                                                           String jurisdiction) {
        ContentLanguage language = contentLanguageResolver.requestLanguage();
        List<ConnectorPreset> presets = search(search, null, category, jurisdiction);
        Map<UUID, ConnectorPresetText> texts = texts(presets, language);
        return presets.stream()
                .sorted(Comparator.comparing(preset -> name(preset, texts.get(preset.getId())),
                        String.CASE_INSENSITIVE_ORDER))
                .map(preset -> toPublic(preset, texts.get(preset.getId())))
                .toList();
    }

    public CatalogConnectorPresetResponse getPublic(String code) {
        ContentLanguage language = contentLanguageResolver.requestLanguage();
        ConnectorPreset preset = requirePreset(code);
        ConnectorPresetText text = connectorPresetTextRepository
                .findByPresetIdAndLanguage(preset.getId(), language)
                .orElse(null);
        return toPublic(preset, text);
    }

    private List<ConnectorPreset> search(String search, String connectorCode,
                                        ContentCategory category, String jurisdiction) {
        return connectorPresetRepository.search(
                escapeLike(search),
                blankToNull(connectorCode),
                category == null ? null : category.name(),
                blankToNull(jurisdiction));
    }

    private ConnectorPreset requirePreset(String code) {
        return connectorPresetRepository.findByCodeAndSourcePresentTrueAndEnabledTrue(code)
                .orElseThrow(() -> new NotFoundStatusException("Connector preset not found: " + code));
    }

    private Map<UUID, ConnectorPresetText> texts(List<ConnectorPreset> presets, ContentLanguage language) {
        if (presets.isEmpty()) {
            return Map.of();
        }
        List<UUID> ids = presets.stream().map(ConnectorPreset::getId).toList();
        return connectorPresetTextRepository.findAllByPresetIdInAndLanguage(ids, language).stream()
                .collect(Collectors.toMap(ConnectorPresetText::getPresetId, Function.identity()));
    }

    private ConnectorPresetResponse toManage(ConnectorPreset preset, ConnectorPresetText text,
                                             ContentLanguage language) {
        return new ConnectorPresetResponse(
                preset.getCode(),
                preset.getConnectorCode(),
                toCard(preset, text),
                presetFormAssembler.assemble(preset, language),
                new ConnectorPresetConnection(preset.getIdentity(), preset.getAvailability()));
    }

    private static CatalogConnectorPresetResponse toPublic(ConnectorPreset preset, ConnectorPresetText text) {
        return new CatalogConnectorPresetResponse(
                preset.getCode(),
                preset.getConnectorCode(),
                new CatalogConnectorPresetResponse.CatalogConnectorPresetCard(
                        name(preset, text),
                        description(preset, text),
                        preset.getIconUrl(),
                        preset.getCategory(),
                        preset.getJurisdiction(),
                        auth(preset.getAuthMethods()),
                        preset.getDocsUrl()),
                new CatalogConnectorPresetResponse.CatalogConnectorPresetConnection(preset.getAvailability()));
    }

    private static ConnectorPresetCard toCard(ConnectorPreset preset, ConnectorPresetText text) {
        return new ConnectorPresetCard(
                name(preset, text),
                description(preset, text),
                preset.getIconUrl(),
                preset.getCategory(),
                preset.getJurisdiction(),
                auth(preset.getAuthMethods()),
                preset.getDocsUrl());
    }

    private static String name(ConnectorPreset preset, ConnectorPresetText text) {
        return text != null && text.getName() != null ? text.getName() : preset.getName();
    }

    private static String description(ConnectorPreset preset, ConnectorPresetText text) {
        return text != null && text.getDescription() != null ? text.getDescription() : preset.getDescription();
    }

    /** The badge: the most specific supported method, or UNKNOWN when none is supported. */
    static ConnectorPresetAuth auth(List<ConnectorPresetAuth> methods) {
        if (methods == null) {
            return ConnectorPresetAuth.UNKNOWN;
        }
        if (methods.contains(ConnectorPresetAuth.OAUTH)) {
            return ConnectorPresetAuth.OAUTH;
        }
        if (methods.contains(ConnectorPresetAuth.TOKEN)) {
            return ConnectorPresetAuth.TOKEN;
        }
        if (methods.contains(ConnectorPresetAuth.NONE)) {
            return ConnectorPresetAuth.NONE;
        }
        return ConnectorPresetAuth.UNKNOWN;
    }

    /** The SQL uses {@code ESCAPE '\'}; {@code %}, {@code _} and {@code \} must not act as wildcards. */
    static String escapeLike(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
