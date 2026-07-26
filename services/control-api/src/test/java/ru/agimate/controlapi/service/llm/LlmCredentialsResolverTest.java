package ru.agimate.controlapi.service.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.agimate.controlapi.database.entities.AgentLlm;
import ru.agimate.controlapi.database.entities.LlmProvider;
import ru.agimate.controlapi.database.entities.LlmProviderModel;
import ru.agimate.controlapi.database.enums.LlmProviderModelStatus;
import ru.agimate.controlapi.database.enums.LlmProviderType;
import ru.agimate.controlapi.database.enums.LlmPurpose;
import ru.agimate.controlapi.database.repositories.AgentLlmRepository;
import ru.agimate.controlapi.database.repositories.LlmProviderModelRepository;
import ru.agimate.controlapi.database.repositories.LlmProviderRepository;
import ru.agimate.controlapi.service.LlmProviderService;
import ru.agimate.controlapi.service.llm.LlmCredentialsResolver.ResolvedLlm;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("LlmCredentialsResolver.resolveForCapability — каскад выбора модели-инструмента")
class LlmCredentialsResolverTest {

    private final UUID agentId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @Mock
    private AgentLlmRepository agentLlmRepository;
    @Mock
    private LlmProviderRepository llmProviderRepository;
    @Mock
    private LlmProviderModelRepository llmProviderModelRepository;
    @Mock
    private LlmProviderService llmProviderService;
    @Mock
    private LlmQuotaService llmQuotaService;
    @InjectMocks
    private LlmCredentialsResolver resolver;

    private LlmProvider provider(String name) {
        return LlmProvider.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .name(name)
                .providerType(LlmProviderType.OPENAI_COMPATIBLE)
                .baseUrl("https://openrouter.ai/api/v1")
                .enabled(true)
                .build();
    }

    private static LlmProviderModel model(String name, List<String> in, List<String> out,
                                          LlmProviderModelStatus status) {
        return LlmProviderModel.builder()
                .id(UUID.randomUUID())
                .model(name)
                .inputModalities(in)
                .outputModalities(out)
                .status(status)
                .build();
    }

    private void stubRegistry(LlmProvider provider, LlmProviderModel... models) {
        when(llmProviderModelRepository.findAllByProviderIdOrderByModel(provider.getId()))
                .thenReturn(List.of(models));
    }

    private void stubNoBinding(LlmPurpose purpose) {
        when(agentLlmRepository.findByAgentIdAndPurpose(agentId, purpose))
                .thenReturn(Optional.empty());
    }

    @Nested
    @DisplayName("ступень 1 — явный purpose-биндинг")
    class ExplicitBinding {

        @Test
        @DisplayName("биндинг побеждает: реестр для выбора не опрашивается, advisory-сверки нет")
        void bindingWins() {
            LlmProvider bound = provider("my-openrouter");
            AgentLlm binding = AgentLlm.builder()
                    .agentId(agentId)
                    .llmProviderId(bound.getId())
                    .model("google/gemini-2.5-flash-image")
                    .purpose(LlmPurpose.IMAGE)
                    .build();
            when(agentLlmRepository.findByAgentIdAndPurpose(agentId, LlmPurpose.IMAGE))
                    .thenReturn(Optional.of(binding));
            when(llmProviderRepository.findById(bound.getId())).thenReturn(Optional.of(bound));
            when(llmProviderService.decryptApiKey(bound)).thenReturn("sk-key");
            when(llmProviderModelRepository.findByProviderIdAndModel(bound.getId(),
                    "google/gemini-2.5-flash-image")).thenReturn(Optional.empty());

            ResolvedLlm resolved = resolver.resolveForCapability(agentId, userId, LlmPurpose.IMAGE);

            assertEquals("google/gemini-2.5-flash-image", resolved.model());
            assertEquals("sk-key", resolved.apiKey());
            assertFalse(resolved.platformFallback());
            assertTrue(resolved.inputModalities().isEmpty(), "нет строки реестра → модальности неизвестны");
            verify(llmQuotaService).check(bound, userId, agentId);
            verify(llmProviderRepository, never()).findAllByUserIdOrderByCreatedAtDesc(any());
        }

        @Test
        @DisplayName("input_modalities берутся из строки реестра резолвнутой модели")
        void inputModalitiesComeFromRegistryRow() {
            LlmProvider bound = provider("my-openrouter");
            AgentLlm binding = AgentLlm.builder()
                    .agentId(agentId)
                    .llmProviderId(bound.getId())
                    .model("deepseek/deepseek-v4-flash")
                    .purpose(LlmPurpose.CHAT)
                    .build();
            when(agentLlmRepository.findByAgentIdAndPurpose(agentId, LlmPurpose.CHAT))
                    .thenReturn(Optional.of(binding));
            when(llmProviderRepository.findById(bound.getId())).thenReturn(Optional.of(bound));
            when(llmProviderService.decryptApiKey(bound)).thenReturn("sk-key");
            when(llmProviderModelRepository.findByProviderIdAndModel(bound.getId(), "deepseek/deepseek-v4-flash"))
                    .thenReturn(Optional.of(model("deepseek/deepseek-v4-flash",
                            List.of("text"), List.of("text"), LlmProviderModelStatus.AVAILABLE)));

            ResolvedLlm resolved = resolver.resolveChat(agentId, userId);

            assertEquals(List.of("text"), resolved.inputModalities());
        }

        @Test
        @DisplayName("провайдер биндинга выключен → LlmProviderDisabledException, без фолбэка на матч")
        void disabledProviderFailsLoudly() {
            LlmProvider disabled = provider("my-openrouter");
            disabled.setEnabled(false);
            AgentLlm binding = AgentLlm.builder()
                    .agentId(agentId)
                    .llmProviderId(disabled.getId())
                    .model("some-model")
                    .purpose(LlmPurpose.IMAGE)
                    .build();
            when(agentLlmRepository.findByAgentIdAndPurpose(agentId, LlmPurpose.IMAGE))
                    .thenReturn(Optional.of(binding));
            when(llmProviderRepository.findById(disabled.getId())).thenReturn(Optional.of(disabled));

            assertThrows(LlmProviderDisabledException.class,
                    () -> resolver.resolveForCapability(agentId, userId, LlmPurpose.IMAGE));
            verify(llmProviderRepository, never()).findAllByUserIdOrderByCreatedAtDesc(any());
        }
    }

    @Nested
    @DisplayName("ступень 2 — капабилити-матч по провайдерам пользователя")
    class CapabilityMatch {

        @Test
        @DisplayName("выбирается первая по имени AVAILABLE-модель с нужной output-модальностью")
        void picksFirstCapableAvailableModel() {
            LlmProvider p = provider("my-openrouter");
            stubNoBinding(LlmPurpose.IMAGE);
            stubNoBinding(LlmPurpose.CHAT);
            when(llmProviderRepository.findAllByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(p));
            stubRegistry(p,
                    model("a-chat-only", List.of("text"), List.of("text"), LlmProviderModelStatus.AVAILABLE),
                    model("b-image-gone", List.of("text"), List.of("image"), LlmProviderModelStatus.UNAVAILABLE),
                    model("c-image", List.of("text"), List.of("image", "text"), LlmProviderModelStatus.AVAILABLE),
                    model("d-image", List.of("text"), List.of("image"), LlmProviderModelStatus.AVAILABLE));
            when(llmProviderService.decryptApiKey(p)).thenReturn("sk-key");
            when(llmProviderModelRepository.findByProviderIdAndModel(p.getId(), "c-image"))
                    .thenReturn(Optional.empty());

            ResolvedLlm resolved = resolver.resolveForCapability(agentId, userId, LlmPurpose.IMAGE);

            assertEquals("c-image", resolved.model());
            assertFalse(resolved.platformFallback());
            verify(llmQuotaService).check(p, userId, agentId);
        }

        @Test
        @DisplayName("VISION смотрит input_modalities: генератор без зрения не подходит")
        void visionMatchesInputModalities() {
            LlmProvider p = provider("my-openrouter");
            stubNoBinding(LlmPurpose.VISION);
            stubNoBinding(LlmPurpose.CHAT);
            when(llmProviderRepository.findAllByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(p));
            stubRegistry(p,
                    model("a-image-gen", List.of("text"), List.of("image"), LlmProviderModelStatus.AVAILABLE),
                    model("b-vision", List.of("text", "image"), List.of("text"), LlmProviderModelStatus.AVAILABLE));
            when(llmProviderService.decryptApiKey(p)).thenReturn("sk-key");
            when(llmProviderModelRepository.findByProviderIdAndModel(p.getId(), "b-vision"))
                    .thenReturn(Optional.empty());

            assertEquals("b-vision",
                    resolver.resolveForCapability(agentId, userId, LlmPurpose.VISION).model());
        }

        @Test
        @DisplayName("провайдер chat-биндинга проверяется первым, даже если он не первый в листинге")
        void chatProviderCheckedFirst() {
            LlmProvider other = provider("other");
            LlmProvider chatBound = provider("chat-bound");
            stubNoBinding(LlmPurpose.IMAGE);
            AgentLlm chatBinding = AgentLlm.builder()
                    .agentId(agentId)
                    .llmProviderId(chatBound.getId())
                    .model("chat-model")
                    .build();
            when(agentLlmRepository.findByAgentIdAndPurpose(agentId, LlmPurpose.CHAT))
                    .thenReturn(Optional.of(chatBinding));
            when(llmProviderRepository.findAllByUserIdOrderByCreatedAtDesc(userId))
                    .thenReturn(List.of(other, chatBound));
            LlmProviderModel capable =
                    model("img", List.of("text"), List.of("image"), LlmProviderModelStatus.AVAILABLE);
            stubRegistry(chatBound, capable);
            when(llmProviderService.decryptApiKey(chatBound)).thenReturn("sk-key");
            when(llmProviderModelRepository.findByProviderIdAndModel(chatBound.getId(), "img"))
                    .thenReturn(Optional.empty());

            ResolvedLlm resolved = resolver.resolveForCapability(agentId, userId, LlmPurpose.IMAGE);

            assertEquals(chatBound.getId(), resolved.provider().getId());
            // до реестра провайдера other дело не дошло — chat-провайдер удовлетворил запрос
            verify(llmProviderModelRepository, never()).findAllByProviderIdOrderByModel(other.getId());
        }
    }

    @Nested
    @DisplayName("ступень 3 — платформенный провайдер и отказ")
    class PlatformAndFailure {

        @Test
        @DisplayName("у пользователя нет подходящей модели → матч по реестру платформы")
        void fallsBackToPlatformRegistry() {
            LlmProvider mine = provider("mine-no-image");
            LlmProvider platform = provider("platform");
            stubNoBinding(LlmPurpose.IMAGE);
            stubNoBinding(LlmPurpose.CHAT);
            when(llmProviderRepository.findAllByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(mine));
            stubRegistry(mine,
                    model("text-only", List.of("text"), List.of("text"), LlmProviderModelStatus.AVAILABLE));
            when(llmProviderService.findUsablePlatformProvider()).thenReturn(Optional.of(platform));
            stubRegistry(platform,
                    model("platform-image", List.of("text"), List.of("image"), LlmProviderModelStatus.AVAILABLE));
            when(llmProviderService.decryptApiKey(platform)).thenReturn("sk-platform");
            when(llmProviderModelRepository.findByProviderIdAndModel(platform.getId(), "platform-image"))
                    .thenReturn(Optional.empty());

            ResolvedLlm resolved = resolver.resolveForCapability(agentId, userId, LlmPurpose.IMAGE);

            assertEquals("platform-image", resolved.model());
            assertTrue(resolved.platformFallback());
            verify(llmQuotaService).check(platform, userId, agentId);
        }

        @Test
        @DisplayName("нигде ничего → NoCapableModelException с внятным текстом")
        void nothingAnywhere() {
            stubNoBinding(LlmPurpose.IMAGE);
            stubNoBinding(LlmPurpose.CHAT);
            when(llmProviderRepository.findAllByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of());
            when(llmProviderService.findUsablePlatformProvider()).thenReturn(Optional.empty());

            NoCapableModelException e = assertThrows(NoCapableModelException.class,
                    () -> resolver.resolveForCapability(agentId, userId, LlmPurpose.IMAGE));
            assertTrue(e.getMessage().contains("generating image"), e.getMessage());
        }

        @Test
        @DisplayName("CHAT сюда не ходит → IllegalArgumentException")
        void chatRejected() {
            assertThrows(IllegalArgumentException.class,
                    () -> resolver.resolveForCapability(agentId, userId, LlmPurpose.CHAT));
        }
    }
}
