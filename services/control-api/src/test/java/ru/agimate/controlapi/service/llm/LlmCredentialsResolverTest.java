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

    /** Платформенная строка узнаётся по владельцу — от этого зависит флаг platformFallback. */
    private LlmProvider platformProvider() {
        LlmProvider platform = provider("platform");
        platform.setUserId(ru.agimate.controlapi.service.SystemSkillBootstrap.SYSTEM_USER_ID);
        return platform;
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
        @DisplayName("input/output_modalities берутся из строки реестра резолвнутой модели")
        void modalitiesComeFromRegistryRow() {
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
                            List.of("text", "image"), List.of("text"), LlmProviderModelStatus.AVAILABLE)));

            ResolvedLlm resolved = resolver.resolveChat(agentId, userId);

            assertEquals(List.of("text", "image"), resolved.inputModalities());
            assertEquals(List.of("text"), resolved.outputModalities());
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

        @Test
        @DisplayName("модель биндинга выпала из листинга → отказ, а не тихая подмена на другую")
        void goneBoundModelFailsWithoutFallback() {
            LlmProvider bound = provider("my-openrouter");
            bound.setPurposePriority(Map.of(LlmPurpose.VISION, List.of("still-alive")));
            AgentLlm binding = AgentLlm.builder()
                    .agentId(agentId)
                    .llmProviderId(bound.getId())
                    .model("retired-vision")
                    .purpose(LlmPurpose.VISION)
                    .build();
            when(agentLlmRepository.findByAgentIdAndPurpose(agentId, LlmPurpose.VISION))
                    .thenReturn(Optional.of(binding));
            when(llmProviderRepository.findById(bound.getId())).thenReturn(Optional.of(bound));
            when(llmProviderModelRepository.findByProviderIdAndModel(bound.getId(), "retired-vision"))
                    .thenReturn(Optional.of(model("retired-vision", List.of("text", "image"),
                            List.of("text"), LlmProviderModelStatus.UNAVAILABLE)));

            NoCapableModelException e = assertThrows(NoCapableModelException.class,
                    () -> resolver.resolveForCapability(agentId, userId, LlmPurpose.VISION));

            assertTrue(e.getMessage().contains("retired-vision"), e.getMessage());
            assertTrue(e.getMessage().contains("no longer listed"), e.getMessage());
            // список того же провайдера не подхватывается: выбор человека молча не подменяем
            verify(llmProviderService, never()).decryptApiKey(any());
            verify(llmQuotaService, never()).check(any(), any(), any());
        }

        @Test
        @DisplayName("chat-биндинг на выпавшую модель → 404 с тем же текстом (в ран уедет NOT_FOUND)")
        void goneChatBindingFails() {
            LlmProvider bound = provider("my-openrouter");
            AgentLlm binding = AgentLlm.builder()
                    .agentId(agentId)
                    .llmProviderId(bound.getId())
                    .model("retired-chat")
                    .purpose(LlmPurpose.CHAT)
                    .build();
            when(agentLlmRepository.findByAgentIdAndPurpose(agentId, LlmPurpose.CHAT))
                    .thenReturn(Optional.of(binding));
            when(llmProviderRepository.findById(bound.getId())).thenReturn(Optional.of(bound));
            when(llmProviderModelRepository.findByProviderIdAndModel(bound.getId(), "retired-chat"))
                    .thenReturn(Optional.of(model("retired-chat", List.of("text"), List.of("text"),
                            LlmProviderModelStatus.UNAVAILABLE)));

            var e = assertThrows(ru.agimate.common.rest.error.NotFoundStatusException.class,
                    () -> resolver.resolveChat(agentId, userId));

            assertTrue(e.getMessage().contains("no longer listed"), e.getMessage());
            verify(llmProviderService, never()).findUsablePlatformProvider();
        }
    }

    @Nested
    @DisplayName("ступень 2 — purpose_priority провайдера chat-биндинга")
    class ChatProviderList {

        /** Биндинг CHAT на провайдере: он же — первое звено цепочки для тулов. */
        private void stubChatBinding(LlmProvider chatBound) {
            AgentLlm chatBinding = AgentLlm.builder()
                    .agentId(agentId)
                    .llmProviderId(chatBound.getId())
                    .model("chat-model")
                    .build();
            when(agentLlmRepository.findByAgentIdAndPurpose(agentId, LlmPurpose.CHAT))
                    .thenReturn(Optional.of(chatBinding));
            when(llmProviderRepository.findById(chatBound.getId())).thenReturn(Optional.of(chatBound));
        }

        @Test
        @DisplayName("берётся первая модель объявленного списка, реестр по модальностям не опрашивается")
        void picksFirstDeclared() {
            LlmProvider p = provider("my-openrouter");
            p.setPurposePriority(Map.of(LlmPurpose.IMAGE, List.of("first-image", "second-image")));
            stubNoBinding(LlmPurpose.IMAGE);
            stubChatBinding(p);
            stubRegistry(p, model("first-image", List.of("text"), List.of("image"),
                    LlmProviderModelStatus.AVAILABLE));
            when(llmProviderService.decryptApiKey(p)).thenReturn("sk-key");

            ResolvedLlm resolved = resolver.resolveForCapability(agentId, userId, LlmPurpose.IMAGE);

            assertEquals("first-image", resolved.model());
            assertFalse(resolved.platformFallback());
            verify(llmQuotaService).check(p, userId, agentId);
            verify(llmProviderRepository, never()).findAllByUserIdOrderByCreatedAtDesc(any());
        }

        @Test
        @DisplayName("выпавшая из листинга модель пропускается — список это ещё и цепочка фолбэков")
        void skipsUnavailable() {
            LlmProvider p = provider("my-openrouter");
            p.setPurposePriority(Map.of(LlmPurpose.IMAGE, List.of("gone", "alive")));
            stubNoBinding(LlmPurpose.IMAGE);
            stubChatBinding(p);
            stubRegistry(p,
                    model("alive", List.of("text"), List.of("image"), LlmProviderModelStatus.AVAILABLE),
                    model("gone", List.of("text"), List.of("image"), LlmProviderModelStatus.UNAVAILABLE));
            when(llmProviderService.decryptApiKey(p)).thenReturn("sk-key");

            assertEquals("alive",
                    resolver.resolveForCapability(agentId, userId, LlmPurpose.IMAGE).model());
        }

        @Test
        @DisplayName("модели нет в реестре вовсе → берётся как есть: настройка до первого refresh законна")
        void takesModelUnknownToRegistry() {
            LlmProvider p = provider("my-openrouter");
            p.setPurposePriority(Map.of(LlmPurpose.VISION, List.of("hand-entered")));
            stubNoBinding(LlmPurpose.VISION);
            stubChatBinding(p);
            stubRegistry(p);
            when(llmProviderService.decryptApiKey(p)).thenReturn("sk-key");

            assertEquals("hand-entered",
                    resolver.resolveForCapability(agentId, userId, LlmPurpose.VISION).model());
        }

        @Test
        @DisplayName("выключенный провайдер chat-биндинга выпадает из цепочки, а не роняет её")
        void disabledChatProviderIsSkipped() {
            LlmProvider disabled = provider("my-openrouter");
            disabled.setEnabled(false);
            LlmProvider platform = platformProvider();
            platform.setPurposePriority(Map.of(LlmPurpose.IMAGE, List.of("platform-image")));
            stubNoBinding(LlmPurpose.IMAGE);
            stubChatBinding(disabled);
            when(llmProviderService.findUsablePlatformProvider()).thenReturn(Optional.of(platform));
            stubRegistry(platform);
            when(llmProviderService.decryptApiKey(platform)).thenReturn("sk-platform");

            assertEquals("platform-image",
                    resolver.resolveForCapability(agentId, userId, LlmPurpose.IMAGE).model());
        }
    }

    @Nested
    @DisplayName("ступень 3 — платформенный провайдер и отказы")
    class PlatformAndFailure {

        @Test
        @DisplayName("у провайдера пользователя нет ключа этого назначения → платформа")
        void fallsBackToPlatformList() {
            LlmProvider mine = provider("mine");
            mine.setPurposePriority(Map.of(LlmPurpose.CHAT, List.of("chat-model")));
            LlmProvider platform = platformProvider();
            platform.setPurposePriority(Map.of(LlmPurpose.IMAGE, List.of("platform-image")));
            stubNoBinding(LlmPurpose.IMAGE);
            AgentLlm chatBinding = AgentLlm.builder()
                    .agentId(agentId).llmProviderId(mine.getId()).model("chat-model").build();
            when(agentLlmRepository.findByAgentIdAndPurpose(agentId, LlmPurpose.CHAT))
                    .thenReturn(Optional.of(chatBinding));
            when(llmProviderRepository.findById(mine.getId())).thenReturn(Optional.of(mine));
            when(llmProviderService.findUsablePlatformProvider()).thenReturn(Optional.of(platform));
            when(llmProviderService.decryptApiKey(platform)).thenReturn("sk-platform");
            stubRegistry(platform);

            ResolvedLlm resolved = resolver.resolveForCapability(agentId, userId, LlmPurpose.IMAGE);

            assertEquals("platform-image", resolved.model());
            assertTrue(resolved.platformFallback());
            verify(llmQuotaService).check(platform, userId, agentId);
        }

        @Test
        @DisplayName("пустой список у своего провайдера — это «выключено»: до платформы не идём")
        void emptyListStopsTheChain() {
            LlmProvider mine = provider("mine");
            mine.setPurposePriority(Map.of(LlmPurpose.IMAGE, List.of()));
            stubNoBinding(LlmPurpose.IMAGE);
            AgentLlm chatBinding = AgentLlm.builder()
                    .agentId(agentId).llmProviderId(mine.getId()).model("chat-model").build();
            when(agentLlmRepository.findByAgentIdAndPurpose(agentId, LlmPurpose.CHAT))
                    .thenReturn(Optional.of(chatBinding));
            when(llmProviderRepository.findById(mine.getId())).thenReturn(Optional.of(mine));

            NoCapableModelException e = assertThrows(NoCapableModelException.class,
                    () -> resolver.resolveForCapability(agentId, userId, LlmPurpose.IMAGE));
            assertTrue(e.getMessage().contains("switched off"), e.getMessage());
            // до платформы дело не дошло: ключ не расшифровывали, квоту не трогали
            verify(llmProviderService, never()).decryptApiKey(any());
            verify(llmQuotaService, never()).check(any(), any(), any());
        }

        @Test
        @DisplayName("все объявленные модели выпали из листинга → отказ с их перечислением")
        void allDeclaredGone() {
            LlmProvider platform = platformProvider();
            platform.setPurposePriority(Map.of(LlmPurpose.IMAGE, List.of("gone-1", "gone-2")));
            stubNoBinding(LlmPurpose.IMAGE);
            stubNoBinding(LlmPurpose.CHAT);
            when(llmProviderService.findUsablePlatformProvider()).thenReturn(Optional.of(platform));
            stubRegistry(platform,
                    model("gone-1", List.of("text"), List.of("image"), LlmProviderModelStatus.UNAVAILABLE),
                    model("gone-2", List.of("text"), List.of("image"), LlmProviderModelStatus.UNAVAILABLE));

            NoCapableModelException e = assertThrows(NoCapableModelException.class,
                    () -> resolver.resolveForCapability(agentId, userId, LlmPurpose.IMAGE));
            assertTrue(e.getMessage().contains("gone-1"), e.getMessage());
            assertTrue(e.getMessage().contains("unavailable"), e.getMessage());
        }

        @Test
        @DisplayName("нигде ничего → NoCapableModelException с обоими выходами в тексте")
        void nothingAnywhere() {
            stubNoBinding(LlmPurpose.IMAGE);
            stubNoBinding(LlmPurpose.CHAT);
            when(llmProviderService.findUsablePlatformProvider()).thenReturn(Optional.empty());

            NoCapableModelException e = assertThrows(NoCapableModelException.class,
                    () -> resolver.resolveForCapability(agentId, userId, LlmPurpose.IMAGE));
            assertTrue(e.getMessage().contains("Bind a IMAGE model"), e.getMessage());
            assertTrue(e.getMessage().contains("IMAGE list"), e.getMessage());
        }

        @Test
        @DisplayName("CHAT сюда не ходит → IllegalArgumentException")
        void chatRejected() {
            assertThrows(IllegalArgumentException.class,
                    () -> resolver.resolveForCapability(agentId, userId, LlmPurpose.CHAT));
        }
    }

    @Nested
    @DisplayName("resolveChat — платформенный фолбэк без биндинга")
    class ChatFallback {

        @Test
        @DisplayName("модель чата берётся из CHAT-списка платформы")
        void takesFirstChatModelOfPlatform() {
            LlmProvider platform = platformProvider();
            platform.setPurposePriority(Map.of(LlmPurpose.CHAT, List.of("free-model")));
            stubNoBinding(LlmPurpose.CHAT);
            when(llmProviderService.findUsablePlatformProvider()).thenReturn(Optional.of(platform));
            stubRegistry(platform);
            when(llmProviderService.decryptApiKey(platform)).thenReturn("sk-platform");

            ResolvedLlm resolved = resolver.resolveChat(agentId, userId);

            assertEquals("free-model", resolved.model());
            assertTrue(resolved.platformFallback());
        }

        @Test
        @DisplayName("платформа без CHAT-списка → 404 с указанием, чего не хватает")
        void platformWithoutChatList() {
            LlmProvider platform = platformProvider();
            stubNoBinding(LlmPurpose.CHAT);
            when(llmProviderService.findUsablePlatformProvider()).thenReturn(Optional.of(platform));

            var e = assertThrows(ru.agimate.common.rest.error.NotFoundStatusException.class,
                    () -> resolver.resolveChat(agentId, userId));
            assertTrue(e.getMessage().contains("no models configured for CHAT"), e.getMessage());
        }
    }
}
