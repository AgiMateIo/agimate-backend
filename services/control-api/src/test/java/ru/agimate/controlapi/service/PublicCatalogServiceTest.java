package ru.agimate.controlapi.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.controlapi.connectors.core.ConnectorLabels;
import ru.agimate.controlapi.controller.catalog.dto.CatalogConnectorRef;
import ru.agimate.controlapi.controller.catalog.dto.CatalogPresetResponse;
import ru.agimate.controlapi.controller.catalog.dto.CatalogSkillResponse;
import ru.agimate.controlapi.database.entities.AgentPreset;
import ru.agimate.controlapi.database.entities.Connector;
import ru.agimate.controlapi.database.entities.Skill;
import ru.agimate.controlapi.database.enums.ContentLanguage;
import ru.agimate.controlapi.database.model.ConnectorRequirement;
import ru.agimate.controlapi.database.repositories.AgentPresetRepository;
import ru.agimate.controlapi.database.repositories.ConnectorRepository;
import ru.agimate.controlapi.database.repositories.SkillRepository;
import ru.agimate.controlapi.service.seed.ContentLanguageResolver;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static ru.agimate.controlapi.service.SystemSkillBootstrap.SYSTEM_USER_ID;

@ExtendWith(MockitoExtension.class)
@DisplayName("PublicCatalogService — каталог без авторизации")
class PublicCatalogServiceTest {

    @Mock
    private AgentPresetRepository agentPresetRepository;
    @Mock
    private SkillRepository skillRepository;
    @Mock
    private ConnectorRepository connectorRepository;
    @Mock
    private ConnectorLabels connectorLabels;
    @Mock
    private ContentLanguageResolver contentLanguageResolver;

    @InjectMocks
    private PublicCatalogService service;

    private final Connector time = Connector.builder().code("time").name("Время").description("d").build();

    @BeforeEach
    void setUp() {
        when(contentLanguageResolver.requestLanguage()).thenReturn(ContentLanguage.EN);
        when(connectorRepository.findAll()).thenReturn(List.of(time));
        lenient().when(connectorLabels.name(time, ContentLanguage.EN)).thenReturn("Time");
        lenient().when(connectorLabels.description(time, ContentLanguage.EN)).thenReturn("Clock and schedules");
    }

    private void catalogue(List<AgentPreset> presets, List<Skill> skills) {
        lenient().when(agentPresetRepository.findAllByLanguageAndEnabledTrueOrderBySortOrderAscNameAsc(ContentLanguage.EN))
                .thenReturn(presets);
        lenient().when(skillRepository.findAll(any(Specification.class), eq(Sort.by("name").ascending())))
                .thenReturn(skills);
    }

    private static AgentPreset preset(String name, String... skillNames) {
        return AgentPreset.builder().name(name).title(name + " title").description("does " + name)
                .language(ContentLanguage.EN).instructions("secret instructions")
                .skillNames(List.of(skillNames)).build();
    }

    private static Skill skill(String name, ContentLanguage language, String... connectors) {
        return Skill.builder().name(name).title(name + " title").description("skill " + name)
                .language(language).userId(SYSTEM_USER_ID).isPublic(true).mdContent("secret body")
                .connectors(ConnectorRequirement.ofCodes(List.of(connectors))).build();
    }

    @Test
    @DisplayName("пресет: только навыки каталога, коннекторы с подписями на языке запроса")
    void presetSkillsAndConnectors() {
        catalogue(List.of(preset("coder", "search", "unpublished")),
                List.of(skill("search", ContentLanguage.EN, "time", "time")));

        CatalogPresetResponse coder = service.preset("coder");

        assertEquals(List.of("search"), coder.skills().stream().map(CatalogPresetResponse.CatalogSkillRef::name).toList());
        assertEquals(List.of(new CatalogConnectorRef("time", "Time", "Clock and schedules")), coder.connectors());
    }

    @Test
    @DisplayName("выключенного пресета нет в каталоге → 404")
    void unknownPreset() {
        catalogue(List.of(preset("coder")), List.of());

        assertThrows(NotFoundStatusException.class, () -> service.preset("hidden"));
    }

    @Test
    @DisplayName("навык: пресеты, в которые он входит")
    void skillPresets() {
        catalogue(List.of(preset("coder", "search"), preset("writer")), List.of(skill("search", ContentLanguage.EN)));

        CatalogSkillResponse search = service.skill("search");

        assertEquals(List.of(new CatalogSkillResponse.CatalogPresetRef("coder", "coder title")), search.presets());
    }

    @Test
    @DisplayName("языковая версия навыка важнее безъязыковой с тем же именем")
    void languageVersionWins() {
        Skill neutral = skill("search", null);
        neutral.setDescription("neutral");
        catalogue(List.of(), List.of(neutral, skill("search", ContentLanguage.EN)));

        List<CatalogSkillResponse> skills = service.skills();

        assertEquals(1, skills.size());
        assertEquals("skill search", skills.getFirst().description());
    }

    @Test
    @DisplayName("неизвестный навык → 404")
    void unknownSkill() {
        catalogue(List.of(), List.of());

        assertThrows(NotFoundStatusException.class, () -> service.skill("ghost"));
    }
}
