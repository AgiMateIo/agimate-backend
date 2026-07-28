package ru.agimate.controlapi.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.agimate.common.rest.error.ConflictStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.controlapi.controller.manage.dto.llm.AgentLlmResponse;
import ru.agimate.controlapi.controller.manage.dto.llm.CreateAgentLlmRequest;
import ru.agimate.controlapi.controller.manage.dto.llm.UpdateAgentLlmRequest;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.entities.AgentLlm;
import ru.agimate.controlapi.database.entities.LlmProvider;
import ru.agimate.controlapi.database.enums.LlmProviderType;
import ru.agimate.controlapi.database.enums.LlmPurpose;
import ru.agimate.controlapi.database.repositories.AgentLlmRepository;
import ru.agimate.controlapi.database.repositories.AgentRepository;
import ru.agimate.controlapi.database.repositories.LlmProviderRepository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AgentLlmService — эффективная модель (source)")
class AgentLlmServiceTest {

    private static final UUID AGENT_ID = UUID.randomUUID();

    @Mock
    private AgentLlmRepository agentLlmRepository;
    @Mock
    private AgentRepository agentRepository;
    @Mock
    private LlmProviderRepository llmProviderRepository;
    @Mock
    private LlmProviderService llmProviderService;

    @InjectMocks
    private AgentLlmService service;

    private static LlmProvider platformProvider() {
        return LlmProvider.builder()
                .id(UUID.randomUUID())
                .userId(SystemSkillBootstrap.SYSTEM_USER_ID)
                .name(LlmProviderService.PLATFORM_PROVIDER_NAME)
                .providerType(LlmProviderType.OPENAI_COMPATIBLE)
                .baseUrl("https://openrouter.ai/api/v1")
                .purposePriority(Map.of(LlmPurpose.CHAT, List.of("gpt-5-mini")))
                .enabled(true)
                .build();
    }

    @Nested
    @DisplayName("listForAgents")
    class ListForAgents {

        @Test
        @DisplayName("агент без привязок → синтетическая запись source=PLATFORM без provider id")
        void platformEntryForAgentWithoutBindings() {
            when(agentLlmRepository.findAllByAgentIdInOrderByAgentIdAscPurposeAsc(List.of(AGENT_ID)))
                    .thenReturn(List.of());
            when(llmProviderService.findUsablePlatformProvider())
                    .thenReturn(Optional.of(platformProvider()));

            Map<UUID, List<AgentLlmResponse>> result = service.listForAgents(List.of(AGENT_ID));

            List<AgentLlmResponse> llms = result.get(AGENT_ID);
            assertEquals(1, llms.size());
            AgentLlmResponse entry = llms.get(0);
            assertEquals(AgentLlmResponse.Source.PLATFORM, entry.source());
            assertEquals("gpt-5-mini", entry.model());
            assertNull(entry.llmProviderId(), "платформенный провайдер не адресуем пользователем");
        }

        @Test
        @DisplayName("агент с привязкой → source=USER, платформенная запись не подмешивается")
        void userBindingsKeepUserSource() {
            UUID providerId = UUID.randomUUID();
            AgentLlm binding = AgentLlm.builder()
                    .agentId(AGENT_ID)
                    .llmProviderId(providerId)
                    .model("user-model")
                    .build();
            LlmProvider provider = LlmProvider.builder()
                    .id(providerId)
                    .name("my-openai")
                    .providerType(LlmProviderType.OPENAI)
                    .enabled(true)
                    .build();
            when(agentLlmRepository.findAllByAgentIdInOrderByAgentIdAscPurposeAsc(List.of(AGENT_ID)))
                    .thenReturn(List.of(binding));
            when(llmProviderRepository.findAllByIdIn(List.of(providerId)))
                    .thenReturn(List.of(provider));
            when(llmProviderService.findUsablePlatformProvider()).thenReturn(Optional.empty());

            Map<UUID, List<AgentLlmResponse>> result = service.listForAgents(List.of(AGENT_ID));

            List<AgentLlmResponse> llms = result.get(AGENT_ID);
            assertEquals(1, llms.size());
            assertEquals(AgentLlmResponse.Source.USER, llms.get(0).source());
            assertEquals(providerId, llms.get(0).llmProviderId());
        }

        @Test
        @DisplayName("платформенный недоступен и привязок нет → агент без записей")
        void emptyWhenNoPlatformAndNoBindings() {
            when(agentLlmRepository.findAllByAgentIdInOrderByAgentIdAscPurposeAsc(List.of(AGENT_ID)))
                    .thenReturn(List.of());
            when(llmProviderService.findUsablePlatformProvider()).thenReturn(Optional.empty());

            Map<UUID, List<AgentLlmResponse>> result = service.listForAgents(List.of(AGENT_ID));

            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("идентичность биндинга — purpose")
    class PurposeIdentity {

        private final UUID userId = UUID.randomUUID();

        private LlmProvider userProvider() {
            LlmProvider provider = LlmProvider.builder()
                    .id(UUID.randomUUID())
                    .userId(userId)
                    .name("my-openai")
                    .providerType(LlmProviderType.OPENAI)
                    .enabled(true)
                    .build();
            when(llmProviderService.requireOwned(provider.getId(), userId)).thenReturn(provider);
            return provider;
        }

        private void stubOwnedAgent() {
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(
                    Agent.builder().id(AGENT_ID).userId(userId).build()));
        }

        @Test
        @DisplayName("второй биндинг на тот же purpose → 409")
        void secondBindingForSamePurposeConflicts() {
            stubOwnedAgent();
            LlmProvider provider = userProvider();
            when(agentLlmRepository.existsByAgentIdAndPurpose(AGENT_ID, LlmPurpose.CHAT)).thenReturn(true);

            CreateAgentLlmRequest request =
                    new CreateAgentLlmRequest(provider.getId(), "gpt-4o", LlmPurpose.CHAT);

            assertThrows(ConflictStatusException.class, () -> service.create(AGENT_ID, userId, request));
            verify(agentLlmRepository, never()).save(any());
        }

        @Test
        @DisplayName("purpose не задан → CHAT по умолчанию")
        void purposeDefaultsToChat() {
            stubOwnedAgent();
            LlmProvider provider = userProvider();
            when(agentLlmRepository.existsByAgentIdAndPurpose(AGENT_ID, LlmPurpose.CHAT)).thenReturn(false);
            when(agentLlmRepository.save(any(AgentLlm.class))).thenAnswer(inv -> inv.getArgument(0));

            AgentLlmResponse response = service.create(AGENT_ID, userId,
                    new CreateAgentLlmRequest(provider.getId(), "gpt-4o", null));

            assertEquals(LlmPurpose.CHAT, response.purpose());
        }

        @Test
        @DisplayName("replace по purpose, которого нет → 404")
        void replaceMissingPurposeNotFound() {
            stubOwnedAgent();
            when(agentLlmRepository.findByAgentIdAndPurpose(AGENT_ID, LlmPurpose.VISION))
                    .thenReturn(Optional.empty());

            UpdateAgentLlmRequest request = new UpdateAgentLlmRequest(UUID.randomUUID(), "gpt-4o");

            assertThrows(NotFoundStatusException.class,
                    () -> service.replace(AGENT_ID, userId, LlmPurpose.VISION, request));
        }

        @Test
        @DisplayName("delete по purpose удаляет найденный биндинг")
        void deleteByPurpose() {
            stubOwnedAgent();
            AgentLlm binding = AgentLlm.builder()
                    .agentId(AGENT_ID)
                    .llmProviderId(UUID.randomUUID())
                    .model("gpt-4o")
                    .purpose(LlmPurpose.IMAGE)
                    .build();
            when(agentLlmRepository.findByAgentIdAndPurpose(AGENT_ID, LlmPurpose.IMAGE))
                    .thenReturn(Optional.of(binding));

            service.delete(AGENT_ID, userId, LlmPurpose.IMAGE);

            verify(agentLlmRepository).delete(binding);
        }
    }
}
