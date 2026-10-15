package ru.agimate.controlapi.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.controlapi.connectors.core.ConnectorLabels;
import ru.agimate.controlapi.controller.catalog.dto.CatalogConnectorRef;
import ru.agimate.controlapi.controller.catalog.dto.CatalogPresetResponse;
import ru.agimate.controlapi.controller.catalog.dto.CatalogSkillResponse;
import ru.agimate.controlapi.database.entities.AgentPreset;
import ru.agimate.controlapi.database.entities.Connector;
import ru.agimate.controlapi.database.entities.Skill;
import ru.agimate.controlapi.database.enums.ContentLanguage;
import ru.agimate.controlapi.database.repositories.AgentPresetRepository;
import ru.agimate.controlapi.database.repositories.ConnectorRepository;
import ru.agimate.controlapi.database.repositories.SkillRepository;
import ru.agimate.controlapi.database.repositories.SkillSpecs;
import ru.agimate.controlapi.service.seed.ContentLanguageResolver;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static ru.agimate.controlapi.service.SystemSkillBootstrap.SYSTEM_USER_ID;

/**
 * The catalogue shown without sign-in, in the request's language: enabled presets and the platform's
 * public skills. What they do, never what they are — a preset's instructions and a skill's body stay
 * behind authentication. User skills are left out even when public: nobody has reviewed them, and two
 * users' skills may share a name, which is the page's address. See docs/decisions/public-catalog.md.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PublicCatalogService {

    private final AgentPresetRepository agentPresetRepository;
    private final SkillRepository skillRepository;
    private final ConnectorRepository connectorRepository;
    private final ConnectorLabels connectorLabels;
    private final ContentLanguageResolver contentLanguageResolver;

    public List<CatalogPresetResponse> presets() {
        ContentLanguage language = contentLanguageResolver.requestLanguage();
        Catalogue catalogue = catalogue(language);
        return catalogue.presets().stream().map(preset -> toResponse(preset, catalogue)).toList();
    }

    public CatalogPresetResponse preset(String name) {
        ContentLanguage language = contentLanguageResolver.requestLanguage();
        Catalogue catalogue = catalogue(language);
        return catalogue.presets().stream()
                .filter(preset -> preset.getName().equals(name))
                .findFirst()
                .map(preset -> toResponse(preset, catalogue))
                .orElseThrow(() -> new NotFoundStatusException("Preset not found: " + name));
    }

    public List<CatalogSkillResponse> skills() {
        ContentLanguage language = contentLanguageResolver.requestLanguage();
        Catalogue catalogue = catalogue(language);
        return catalogue.skills().values().stream().map(skill -> toResponse(skill, catalogue)).toList();
    }

    public CatalogSkillResponse skill(String name) {
        ContentLanguage language = contentLanguageResolver.requestLanguage();
        Catalogue catalogue = catalogue(language);
        Skill skill = catalogue.skills().get(name);
        if (skill == null) {
            throw new NotFoundStatusException("Skill not found: " + name);
        }
        return toResponse(skill, catalogue);
    }

    public List<CatalogConnectorRef> connectors() {
        ContentLanguage language = contentLanguageResolver.requestLanguage();
        return connectorRepository.findAll().stream()
                .map(connector -> toRef(connector, language))
                .sorted(Comparator.comparing(CatalogConnectorRef::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /**
     * One language's catalogue whole: a dozen presets and a couple of dozen skills, and every page
     * links presets, skills and connectors to each other — loading it all is cheaper than chasing
     * each reference.
     */
    private Catalogue catalogue(ContentLanguage language) {
        List<AgentPreset> presets = agentPresetRepository.findAllByLanguageAndEnabledTrueOrderBySortOrderAscNameAsc(language);
        List<Skill> rows = skillRepository.findAll(SkillSpecs.ownedBy(SYSTEM_USER_ID)
                        .and(SkillSpecs.isPublic())
                        .and(SkillSpecs.inCatalogue(language))
                        .and(SkillSpecs.notDeleted()),
                Sort.by("name").ascending());
        // A name resolves to its language version before the language-less one, as everywhere else.
        Map<String, Skill> skills = new LinkedHashMap<>();
        for (Skill skill : rows) {
            skills.merge(skill.getName(), skill, (current, other) -> current.getLanguage() != null ? current : other);
        }
        Map<String, Connector> connectors = connectorRepository.findAll().stream()
                .collect(Collectors.toMap(Connector::getCode, Function.identity()));
        return new Catalogue(language, presets, skills, connectors);
    }

    private CatalogPresetResponse toResponse(AgentPreset preset, Catalogue catalogue) {
        List<Skill> skills = preset.getSkillNames().stream()
                .map(catalogue.skills()::get)
                .filter(Objects::nonNull)
                .toList();
        return new CatalogPresetResponse(
                preset.getName(),
                preset.getTitle(),
                preset.getDescription(),
                preset.getAgentType(),
                preset.getCategory(),
                List.copyOf(preset.getTags()),
                skills.stream()
                        .map(skill -> new CatalogPresetResponse.CatalogSkillRef(skill.getName(), title(skill),
                                skill.getDescription()))
                        .toList(),
                connectorRefs(skills.stream().flatMap(skill -> skill.getConnectorCodes().stream()).toList(), catalogue));
    }

    private CatalogSkillResponse toResponse(Skill skill, Catalogue catalogue) {
        return new CatalogSkillResponse(
                skill.getName(),
                title(skill),
                skill.getDescription(),
                skill.getCategory(),
                List.copyOf(skill.getTags()),
                connectorRefs(skill.getConnectorCodes(), catalogue),
                catalogue.presets().stream()
                        .filter(preset -> preset.getSkillNames().contains(skill.getName()))
                        .map(preset -> new CatalogSkillResponse.CatalogPresetRef(preset.getName(), preset.getTitle()))
                        .toList());
    }

    private List<CatalogConnectorRef> connectorRefs(Collection<String> codes, Catalogue catalogue) {
        Set<String> distinct = new LinkedHashSet<>(codes);
        return distinct.stream()
                .map(catalogue.connectors()::get)
                .filter(Objects::nonNull)
                .map(connector -> toRef(connector, catalogue.language()))
                .toList();
    }

    private CatalogConnectorRef toRef(Connector connector, ContentLanguage language) {
        return new CatalogConnectorRef(connector.getCode(), connectorLabels.name(connector, language),
                connectorLabels.description(connector, language));
    }

    private static String title(Skill skill) {
        return skill.getTitle() != null ? skill.getTitle() : skill.getName();
    }

    private record Catalogue(ContentLanguage language, List<AgentPreset> presets, Map<String, Skill> skills,
                             Map<String, Connector> connectors) {
    }
}
