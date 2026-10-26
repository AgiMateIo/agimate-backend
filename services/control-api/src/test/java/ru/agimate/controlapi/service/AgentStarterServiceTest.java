package ru.agimate.controlapi.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.entities.AgentPreset;
import ru.agimate.controlapi.database.entities.AgentSkill;
import ru.agimate.controlapi.database.entities.AgentStarter;
import ru.agimate.controlapi.database.entities.Skill;
import ru.agimate.controlapi.database.enums.ContentLanguage;
import ru.agimate.controlapi.database.repositories.AgentRepository;
import ru.agimate.controlapi.database.repositories.AgentSkillRepository;
import ru.agimate.controlapi.database.repositories.AgentStarterRepository;
import ru.agimate.controlapi.database.repositories.SkillRepository;
import ru.agimate.controlapi.service.dto.agent.AgentStarters;
import ru.agimate.controlapi.service.dto.agent.AgentStarters.OwnStarter;
import ru.agimate.controlapi.service.seed.ContentLanguageResolver;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AgentStarterService — приветствие и подсказки агента")
class AgentStarterServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID AGENT_ID = UUID.randomUUID();

    @Mock
    private AgentStarterRepository agentStarterRepository;
    @Mock
    private AgentRepository agentRepository;
    @Mock
    private AgentSkillRepository agentSkillRepository;
    @Mock
    private SkillRepository skillRepository;
    @Mock
    private AgentSkillService agentSkillService;
    @Mock
    private ContentLanguageResolver contentLanguageResolver;

    @InjectMocks
    private AgentStarterService service;

    private Agent agent;

    @BeforeEach
    void setUp() {
        agent = Agent.builder().id(AGENT_ID).userId(USER_ID).name("a").greeting("Привет").build();
        lenient().when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(agent));
        lenient().when(agentSkillService.gate(AGENT_ID))
                .thenReturn(new AgentSkillService.SkillGate(Map.of(), List.of()));
    }

    private static AgentStarter own(String text, int order) {
        return AgentStarter.builder().id(UUID.randomUUID()).agentId(AGENT_ID).text(text).sortOrder(order).build();
    }

    private static Skill skill(String title, String... starters) {
        return Skill.builder().id(UUID.randomUUID()).name(title.toLowerCase()).title(title)
                .starters(new ArrayList<>(List.of(starters))).mdContent("b").userId(USER_ID).build();
    }

    private static AgentSkill binding(Skill skill) {
        return AgentSkill.builder().id(UUID.randomUUID()).agentId(AGENT_ID).skillId(skill.getId())
                .userId(USER_ID).build();
    }

    @Nested
    @DisplayName("get")
    class Get {

        @Test
        @DisplayName("свои по sort_order, затем навыков, прошедших гейт; скрытые не отдаются")
        void ordersAndFilters() {
            Skill astro = skill("Astro", "Построй натальную карту", "Транзиты на месяц");
            Skill blocked = skill("Telegram", "Напиши в канал");
            AgentSkill astroBinding = binding(astro);
            AgentSkill blockedBinding = binding(blocked);
            AgentStarter hidden = AgentStarter.builder().agentId(AGENT_ID).agentSkillId(astroBinding.getId())
                    .text("Транзиты на месяц").hidden(true).build();
            when(agentStarterRepository.findByAgentId(AGENT_ID))
                    .thenReturn(List.of(own("Вторая", 1), hidden, own("Первая", 0)));
            when(agentSkillService.gate(AGENT_ID)).thenReturn(new AgentSkillService.SkillGate(
                    Map.of(astro.getId(), Set.of()), List.of()));
            when(agentSkillRepository.findByAgentId(AGENT_ID)).thenReturn(List.of(astroBinding, blockedBinding));
            when(skillRepository.findByIdInNotDeleted(anyCollection())).thenReturn(List.of(astro));

            AgentStarters result = service.get(AGENT_ID, USER_ID);

            assertEquals("Привет", result.greeting());
            assertEquals(List.of("Первая", "Вторая", "Построй натальную карту"),
                    result.starters().stream().map(AgentStarters.Starter::text).toList());
            AgentStarters.Starter fromSkill = result.starters().get(2);
            assertEquals(astroBinding.getId(), fromSkill.agentSkillId());
            assertEquals("Astro", fromSkill.skillTitle());
            assertNull(fromSkill.id());
        }

        @Test
        @DisplayName("чужой агент — 404, а не 403: его существование не раскрывается")
        void foreignAgent() {
            assertThrows(NotFoundStatusException.class, () -> service.get(AGENT_ID, UUID.randomUUID()));
        }
    }

    @Nested
    @DisplayName("save")
    class Save {

        @Test
        @DisplayName("строка с id обновляется на месте, без id — новая, пропавшая удаляется")
        void upsertsById() {
            AgentStarter kept = own("Старая", 0);
            AgentStarter dropped = own("Удалить", 1);
            when(agentStarterRepository.findByAgentId(AGENT_ID)).thenReturn(List.of(kept, dropped));

            service.save(AGENT_ID, USER_ID, "  ", List.of(
                    new OwnStarter(null, " Новая "), new OwnStarter(kept.getId(), "Правленая")));

            assertNull(agent.getGreeting(), "пустое приветствие стирает его");
            assertEquals("Правленая", kept.getText());
            assertEquals(1, kept.getSortOrder());
            verify(agentStarterRepository).deleteAll(org.mockito.ArgumentMatchers.<Iterable<AgentStarter>>argThat(rows ->
                    List.copyOf((Collection<AgentStarter>) rows).equals(List.of(dropped))));
            verify(agentStarterRepository).saveAll(org.mockito.ArgumentMatchers.<Collection<AgentStarter>>argThat(rows ->
                    rows.size() == 2 && rows.stream().anyMatch(r -> r.getId() == null && r.getText().equals("Новая")
                            && r.getSortOrder() == 0)));
        }

        @Test
        @DisplayName("id чужой или подсказки навыка — 400")
        void unknownId() {
            when(agentStarterRepository.findByAgentId(AGENT_ID)).thenReturn(List.of());
            assertThrows(BadRequestStatusException.class, () -> service.save(AGENT_ID, USER_ID, null,
                    List.of(new OwnStarter(UUID.randomUUID(), "Текст"))));
        }

        @Test
        @DisplayName("правила текста действуют и здесь")
        void validates() {
            assertThrows(BadRequestStatusException.class, () -> service.save(AGENT_ID, USER_ID, null,
                    List.of(new OwnStarter(null, "Раз\nдва"))));
        }
    }

    @Nested
    @DisplayName("hide")
    class Hide {

        @Test
        @DisplayName("скрывается текст, который сейчас есть у навыка привязки")
        void hidesCarriedText() {
            Skill astro = skill("Astro", "Карта дня");
            AgentSkill b = binding(astro);
            when(agentSkillRepository.findById(b.getId())).thenReturn(Optional.of(b));
            when(skillRepository.findById(astro.getId())).thenReturn(Optional.of(astro));

            service.hide(AGENT_ID, USER_ID, b.getId(), " Карта дня ");

            verify(agentStarterRepository).hideSkillStarter(AGENT_ID, b.getId(), "Карта дня");
        }

        @Test
        @DisplayName("текста нет в навыке — 404, мусорная строка не заводится")
        void rejectsUnknownText() {
            Skill astro = skill("Astro", "Карта дня");
            AgentSkill b = binding(astro);
            when(agentSkillRepository.findById(b.getId())).thenReturn(Optional.of(b));
            when(skillRepository.findById(astro.getId())).thenReturn(Optional.of(astro));

            assertThrows(NotFoundStatusException.class, () -> service.hide(AGENT_ID, USER_ID, b.getId(), "Другое"));
            verify(agentStarterRepository, never()).hideSkillStarter(any(), any(), anyString());
        }

        @Test
        @DisplayName("привязка другого агента — 404")
        void foreignBinding() {
            AgentSkill b = AgentSkill.builder().id(UUID.randomUUID()).agentId(UUID.randomUUID()).build();
            when(agentSkillRepository.findById(b.getId())).thenReturn(Optional.of(b));
            assertThrows(NotFoundStatusException.class, () -> service.hide(AGENT_ID, USER_ID, b.getId(), "x"));
        }
    }

    @Nested
    @DisplayName("adoptPreset")
    class Adopt {

        @Test
        @DisplayName("агент без приветствия получает его, агент со своими подсказками их сохраняет")
        void fillsOnlyWhatIsMissing() {
            AgentPreset preset = AgentPreset.builder().name("astrologer").language(ContentLanguage.RU)
                    .greeting("Я Селена").starters(new ArrayList<>(List.of("Карта дня"))).build();
            Agent bare = Agent.builder().id(UUID.randomUUID()).build();
            Agent withStarters = Agent.builder().id(UUID.randomUUID()).build();
            when(contentLanguageResolver.defaultLanguage()).thenReturn(ContentLanguage.RU);
            when(agentRepository.findPresetAgentsMissingStarters("astrologer", ContentLanguage.RU, true))
                    .thenReturn(List.of(bare, withStarters));
            when(agentStarterRepository.existsByAgentIdAndAgentSkillIdIsNull(bare.getId())).thenReturn(false);
            when(agentStarterRepository.existsByAgentIdAndAgentSkillIdIsNull(withStarters.getId())).thenReturn(true);

            service.adoptPreset(preset);

            assertEquals("Я Селена", bare.getGreeting());
            assertEquals("Я Селена", withStarters.getGreeting());
            verify(agentStarterRepository).saveAll(org.mockito.ArgumentMatchers.<Collection<AgentStarter>>argThat(rows ->
                    rows.size() == 1 && rows.iterator().next().getAgentId().equals(bare.getId())));
            verify(agentStarterRepository, never()).saveAll(org.mockito.ArgumentMatchers.<Collection<AgentStarter>>argThat(
                    rows -> rows.stream().anyMatch(r -> r.getAgentId().equals(withStarters.getId()))));
        }

        @Test
        @DisplayName("пресет не языка установки не трогает агентов без языка")
        void languageOfUnsetAgents() {
            AgentPreset preset = AgentPreset.builder().name("coder").language(ContentLanguage.EN).build();
            when(contentLanguageResolver.defaultLanguage()).thenReturn(ContentLanguage.RU);

            service.adoptPreset(preset);

            verify(agentRepository).findPresetAgentsMissingStarters(eq("coder"), eq(ContentLanguage.EN), eq(false));
        }
    }
}
