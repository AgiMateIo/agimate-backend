package ru.agimate.controlapi.service.llm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.entities.AgentLlm;
import ru.agimate.controlapi.database.entities.LlmProvider;
import ru.agimate.controlapi.database.entities.LlmProviderModel;
import ru.agimate.controlapi.database.entities.PlatformLlm;
import ru.agimate.controlapi.database.enums.AgentType;
import ru.agimate.controlapi.database.enums.LlmProviderModelStatus;
import ru.agimate.controlapi.database.enums.LlmProviderType;
import ru.agimate.controlapi.database.enums.LlmPurpose;
import ru.agimate.controlapi.database.enums.LlmSource;
import ru.agimate.controlapi.database.repositories.AgentLlmRepository;
import ru.agimate.controlapi.database.repositories.AgentRepository;
import ru.agimate.controlapi.database.repositories.LlmProviderModelRepository;
import ru.agimate.controlapi.database.repositories.LlmProviderRepository;
import ru.agimate.controlapi.database.repositories.PlatformLlmRepository;
import ru.agimate.controlapi.service.LlmProviderService;
import ru.agimate.controlapi.service.SystemSkillBootstrap;
import ru.agimate.controlapi.service.llm.LlmCredentialsResolver.ResolvedLlm;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("LlmCredentialsResolver.resolveFor — первая пригодная привязка цепочки")
class LlmCredentialsResolverTest {

    private final UUID agentId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @Mock
    private AgentRepository agentRepository;
    @Mock
    private AgentLlmRepository agentLlmRepository;
    @Mock
    private PlatformLlmRepository platformLlmRepository;
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

    /** The agent under test; a test switches the source before calling the resolver. */
    private LlmSource agentSource = LlmSource.BYOK;
    private AgentType agentType = AgentType.GENERIC;

    @BeforeEach
    void stubAgent() {
        // lenient: the static registryDefaults tests never call resolveFor
        lenient().when(agentRepository.findById(agentId)).thenAnswer(inv -> Optional.of(Agent.builder()
                .id(agentId).userId(userId).type(agentType).llmSource(agentSource).build()));
    }

    private void platformAgent() {
        agentSource = LlmSource.PLATFORM;
    }

    // ---- helpers ---------------------------------------------------------------------------

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

    private LlmProvider platformProvider() {
        LlmProvider platform = provider("platform");
        platform.setUserId(SystemSkillBootstrap.SYSTEM_USER_ID);
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

    private static LlmProviderModel alive(String name, List<String> in, List<String> out) {
        return model(name, in, out, LlmProviderModelStatus.AVAILABLE);
    }

    private static LlmProviderModel textModel(String name) {
        return alive(name, List.of("text"), List.of("text"));
    }

    private static AgentLlm binding(LlmProvider provider, String model, LlmPurpose purpose, int priority) {
        return AgentLlm.builder()
                .agentId(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .llmProviderId(provider.getId())
                .model(model)
                .purpose(purpose)
                .priority(priority)
                .build();
    }

    private static PlatformLlm platformBinding(LlmProvider provider, String model, LlmPurpose purpose,
                                               int priority) {
        return PlatformLlm.builder()
                .llmProviderId(provider.getId())
                .model(model)
                .purpose(purpose)
                .priority(priority)
                .build();
    }

    /** The purpose's own chain: rows already in priority order. */
    private void stubChain(LlmPurpose purpose, AgentLlm... bindings) {
        when(agentLlmRepository.findAllByAgentIdAndPurposeOrderByPriorityAsc(agentId, purpose))
                .thenReturn(List.of(bindings));
    }

    private void stubNoChain(LlmPurpose purpose) {
        when(agentLlmRepository.findAllByAgentIdAndPurposeOrderByPriorityAsc(agentId, purpose))
                .thenReturn(List.of());
    }

    /** The platform purpose's rows, already in priority order. */
    private void stubPlatformChain(LlmPurpose purpose, PlatformLlm... bindings) {
        when(platformLlmRepository.findAllByPurposeOrderByPriorityAsc(purpose))
                .thenReturn(List.of(bindings));
    }

    private void stubNoPlatformChain(LlmPurpose purpose) {
        when(platformLlmRepository.findAllByPurposeOrderByPriorityAsc(purpose))
                .thenReturn(List.of());
    }

    /** Providers of a chain are read in one query by id. */
    private void stubProviders(LlmProvider... providers) {
        when(llmProviderRepository.findAllByIdIn(
                Arrays.stream(providers).map(LlmProvider::getId).toList()))
                .thenReturn(List.of(providers));
    }

    private void stubRegistry(LlmProvider provider, LlmProviderModel... models) {
        when(llmProviderModelRepository.findAllByLlmProviderIdOrderByModel(provider.getId()))
                .thenReturn(List.of(models));
    }

    private void stubRow(LlmProvider provider, LlmProviderModel row) {
        when(llmProviderModelRepository.findByLlmProviderIdAndModel(provider.getId(), row.getModel()))
                .thenReturn(Optional.of(row));
    }

    // ---- CHAT ------------------------------------------------------------------------------

    @Nested
    @DisplayName("CHAT — явная цепочка привязок BYOK")
    class ChatChain {

        @Test
        @DisplayName("берётся первая по priority привязка, платформа не опрашивается")
        void firstByPriority() {
            LlmProvider first = provider("first");
            LlmProvider second = provider("second");
            stubChain(LlmPurpose.CHAT,
                    binding(first, "primary", LlmPurpose.CHAT, 0),
                    binding(second, "backup", LlmPurpose.CHAT, 1));
            stubProviders(first, second);
            when(llmProviderModelRepository.findByLlmProviderIdAndModel(first.getId(), "primary"))
                    .thenReturn(Optional.empty());
            when(llmProviderService.decryptApiKey(first)).thenReturn("sk-first");

            ResolvedLlm resolved = resolver.resolveFor(agentId, userId, LlmPurpose.CHAT);

            assertEquals("primary", resolved.model());
            assertEquals("sk-first", resolved.apiKey());
            assertFalse(resolved.platformFallback());
            verify(llmQuotaService).check(first, userId, agentId);
            verifyNoInteractions(platformLlmRepository);
        }

        @Test
        @DisplayName("выключенный провайдер пропускается — вызов переходит на следующую привязку")
        void skipsDisabledProvider() {
            LlmProvider disabled = provider("off");
            disabled.setEnabled(false);
            LlmProvider alive = provider("on");
            stubChain(LlmPurpose.CHAT,
                    binding(disabled, "dead", LlmPurpose.CHAT, 0),
                    binding(alive, "alive", LlmPurpose.CHAT, 1));
            stubProviders(disabled, alive);
            when(llmProviderModelRepository.findByLlmProviderIdAndModel(alive.getId(), "alive"))
                    .thenReturn(Optional.empty());
            when(llmProviderService.decryptApiKey(alive)).thenReturn("sk-alive");

            assertEquals("alive", resolver.resolveFor(agentId, userId, LlmPurpose.CHAT).model());

            verify(llmQuotaService, never()).check(eq(disabled), any(), any());
        }

        @Test
        @DisplayName("модель, выпавшая из листинга, пропускается")
        void skipsUnavailableModel() {
            LlmProvider p = provider("p");
            stubChain(LlmPurpose.CHAT,
                    binding(p, "gone", LlmPurpose.CHAT, 0),
                    binding(p, "alive", LlmPurpose.CHAT, 1));
            stubProviders(p);
            stubRow(p, model("gone", List.of("text"), List.of("text"), LlmProviderModelStatus.UNAVAILABLE));
            when(llmProviderModelRepository.findByLlmProviderIdAndModel(p.getId(), "alive"))
                    .thenReturn(Optional.of(textModel("alive")));
            when(llmProviderService.decryptApiKey(p)).thenReturn("sk");

            assertEquals("alive", resolver.resolveFor(agentId, userId, LlmPurpose.CHAT).model());
        }

        @Test
        @DisplayName("исчерпанная квота первого провайдера пропускается")
        void skipsExhaustedQuota() {
            LlmProvider broke = provider("broke");
            LlmProvider rich = provider("rich");
            stubChain(LlmPurpose.CHAT,
                    binding(broke, "first", LlmPurpose.CHAT, 0),
                    binding(rich, "second", LlmPurpose.CHAT, 1));
            stubProviders(broke, rich);
            when(llmProviderModelRepository.findByLlmProviderIdAndModel(broke.getId(), "first"))
                    .thenReturn(Optional.empty());
            when(llmProviderModelRepository.findByLlmProviderIdAndModel(rich.getId(), "second"))
                    .thenReturn(Optional.empty());
            doThrow(new QuotaExceededException("Лимит «broke» исчерпан"))
                    .when(llmQuotaService).check(broke, userId, agentId);
            when(llmProviderService.decryptApiKey(rich)).thenReturn("sk-rich");

            assertEquals("second", resolver.resolveFor(agentId, userId, LlmPurpose.CHAT).model());
        }

        @Test
        @DisplayName("все кандидаты упёрлись в квоту → QuotaExceededException с текстом LlmQuotaService")
        void allQuotaExhausted() {
            LlmProvider first = provider("first");
            LlmProvider second = provider("second");
            stubChain(LlmPurpose.CHAT,
                    binding(first, "m1", LlmPurpose.CHAT, 0),
                    binding(second, "m2", LlmPurpose.CHAT, 1));
            stubProviders(first, second);
            when(llmProviderModelRepository.findByLlmProviderIdAndModel(first.getId(), "m1"))
                    .thenReturn(Optional.empty());
            when(llmProviderModelRepository.findByLlmProviderIdAndModel(second.getId(), "m2"))
                    .thenReturn(Optional.empty());
            doThrow(new QuotaExceededException("Дневной лимит «first» исчерпан"))
                    .when(llmQuotaService).check(first, userId, agentId);
            doThrow(new QuotaExceededException("Дневной лимит «second» исчерпан"))
                    .when(llmQuotaService).check(second, userId, agentId);

            QuotaExceededException e = assertThrows(QuotaExceededException.class,
                    () -> resolver.resolveFor(agentId, userId, LlmPurpose.CHAT));

            assertTrue(e.getMessage().contains("«first»"), e.getMessage());
            assertTrue(e.getMessage().contains("«second»"), e.getMessage());
            verify(llmProviderService, never()).decryptApiKey(any());
        }

        @Test
        @DisplayName("все кандидаты выключены → LlmProviderDisabledException")
        void allDisabled() {
            LlmProvider one = provider("one");
            one.setEnabled(false);
            LlmProvider two = provider("two");
            two.setEnabled(false);
            stubChain(LlmPurpose.CHAT,
                    binding(one, "m1", LlmPurpose.CHAT, 0),
                    binding(two, "m2", LlmPurpose.CHAT, 1));
            stubProviders(one, two);

            assertThrows(LlmProviderDisabledException.class,
                    () -> resolver.resolveFor(agentId, userId, LlmPurpose.CHAT));
            verify(llmProviderService, never()).decryptApiKey(any());
        }

        @Test
        @DisplayName("непригодны все по прочим причинам → NotFoundStatusException (в ран уедет NOT_FOUND)")
        void nothingEligibleIsNotFound() {
            LlmProvider p = provider("p");
            stubChain(LlmPurpose.CHAT, binding(p, "gone", LlmPurpose.CHAT, 0));
            stubProviders(p);
            stubRow(p, model("gone", List.of("text"), List.of("text"), LlmProviderModelStatus.UNAVAILABLE));

            NotFoundStatusException e = assertThrows(NotFoundStatusException.class,
                    () -> resolver.resolveFor(agentId, userId, LlmPurpose.CHAT));

            assertTrue(e.getMessage().contains("gone"), e.getMessage());
            verifyNoInteractions(platformLlmRepository);
        }

        @Test
        @DisplayName("явные привязки закрыты: платформа не подставляется даже когда все непригодны")
        void explicitChainNeverReachesPlatform() {
            LlmProvider disabled = provider("off");
            disabled.setEnabled(false);
            stubChain(LlmPurpose.CHAT, binding(disabled, "dead", LlmPurpose.CHAT, 0));
            stubProviders(disabled);

            assertThrows(LlmProviderDisabledException.class,
                    () -> resolver.resolveFor(agentId, userId, LlmPurpose.CHAT));
            verifyNoInteractions(platformLlmRepository);
        }

        @Test
        @DisplayName("BYOK без CHAT — штатный отказ, платформа не подставляется")
        void byokWithoutChatIsNotFound() {
            stubNoChain(LlmPurpose.CHAT);

            NotFoundStatusException e = assertThrows(NotFoundStatusException.class,
                    () -> resolver.resolveFor(agentId, userId, LlmPurpose.CHAT));

            assertTrue(e.getMessage().contains("BYOK"), e.getMessage());
            verifyNoInteractions(platformLlmRepository);
        }

        @Test
        @DisplayName("смена модели между вызовами: выпавшая привязка уступает следующей")
        void picksNextBindingOnTheSecondCall() {
            LlmProvider p = provider("p");
            stubChain(LlmPurpose.CHAT,
                    binding(p, "first", LlmPurpose.CHAT, 0),
                    binding(p, "second", LlmPurpose.CHAT, 1));
            stubProviders(p);
            when(llmProviderModelRepository.findByLlmProviderIdAndModel(p.getId(), "first"))
                    .thenReturn(Optional.of(textModel("first")));
            when(llmProviderModelRepository.findByLlmProviderIdAndModel(p.getId(), "second"))
                    .thenReturn(Optional.of(textModel("second")));
            when(llmProviderService.decryptApiKey(p)).thenReturn("sk");

            assertEquals("first", resolver.resolveFor(agentId, userId, LlmPurpose.CHAT).model());

            // реестр обновился между вызовами — резолвер ничего не кеширует
            when(llmProviderModelRepository.findByLlmProviderIdAndModel(p.getId(), "first"))
                    .thenReturn(Optional.of(model("first", List.of("text"), List.of("text"),
                            LlmProviderModelStatus.UNAVAILABLE)));

            assertEquals("second", resolver.resolveFor(agentId, userId, LlmPurpose.CHAT).model());
        }
    }

    @Nested
    @DisplayName("CHAT — платформенная цепочка PLATFORM-агента")
    class ChatPlatform {

        @Test
        @DisplayName("модель берётся из platform_llms[CHAT], привязки агента не читаются")
        void takesFirstChatModelOfPlatform() {
            platformAgent();
            LlmProvider platform = platformProvider();
            stubPlatformChain(LlmPurpose.CHAT, platformBinding(platform, "free-model", LlmPurpose.CHAT, 0));
            stubProviders(platform);
            when(llmProviderService.decryptApiKey(platform)).thenReturn("sk-platform");

            ResolvedLlm resolved = resolver.resolveFor(agentId, userId, LlmPurpose.CHAT);

            assertEquals("free-model", resolved.model());
            assertTrue(resolved.platformFallback());
            verifyNoInteractions(agentLlmRepository);
        }

        @Test
        @DisplayName("выпавшая модель платформенной цепочки пропускается — берётся следующая")
        void skipsUnavailablePlatformModel() {
            platformAgent();
            LlmProvider platform = platformProvider();
            stubPlatformChain(LlmPurpose.CHAT,
                    platformBinding(platform, "gone", LlmPurpose.CHAT, 0),
                    platformBinding(platform, "alive", LlmPurpose.CHAT, 1));
            stubProviders(platform);
            stubRow(platform, model("gone", List.of("text"), List.of("text"),
                    LlmProviderModelStatus.UNAVAILABLE));
            stubRow(platform, textModel("alive"));
            when(llmProviderService.decryptApiKey(platform)).thenReturn("sk-platform");

            assertEquals("alive", resolver.resolveFor(agentId, userId, LlmPurpose.CHAT).model());
        }

        @Test
        @DisplayName("пустая платформенная цепочка CHAT → 404 с указанием, чего не хватает")
        void platformWithoutChatChain() {
            platformAgent();
            stubNoPlatformChain(LlmPurpose.CHAT);

            NotFoundStatusException e = assertThrows(NotFoundStatusException.class,
                    () -> resolver.resolveFor(agentId, userId, LlmPurpose.CHAT));
            assertTrue(e.getMessage().contains("declares no CHAT models"), e.getMessage());
        }

        @Test
        @DisplayName("выключенный платформенный провайдер остаётся кандидатом → FAILED_PRECONDITION")
        void disabledPlatformProviderIsNotSilentlyDropped() {
            platformAgent();
            LlmProvider platform = platformProvider();
            platform.setEnabled(false);
            stubPlatformChain(LlmPurpose.CHAT, platformBinding(platform, "m", LlmPurpose.CHAT, 0));
            stubProviders(platform);

            assertThrows(LlmProviderDisabledException.class,
                    () -> resolver.resolveFor(agentId, userId, LlmPurpose.CHAT));
        }
    }

    // ---- ROUTINE ---------------------------------------------------------------------------

    @Nested
    @DisplayName("ROUTINE — рутина платформы")
    class Routine {

        @Test
        @DisplayName("явная ROUTINE-цепочка побеждает и на платформу не уходит")
        void explicitRoutineChainWins() {
            LlmProvider p = provider("mine");
            stubChain(LlmPurpose.ROUTINE, binding(p, "cheap", LlmPurpose.ROUTINE, 0));
            stubProviders(p);
            when(llmProviderModelRepository.findByLlmProviderIdAndModel(p.getId(), "cheap"))
                    .thenReturn(Optional.empty());
            when(llmProviderService.decryptApiKey(p)).thenReturn("sk");

            assertEquals("cheap", resolver.resolveFor(agentId, userId, LlmPurpose.ROUTINE).model());
            verifyNoInteractions(platformLlmRepository);
        }

        @Test
        @DisplayName("ROUTINE-список провайдера, через которого агент уже говорит")
        void fromChatProviderList() {
            LlmProvider p = provider("my-openrouter");
            p.setPurposePriority(Map.of(LlmPurpose.ROUTINE, List.of("cheap-model")));
            stubNoChain(LlmPurpose.ROUTINE);
            stubChain(LlmPurpose.CHAT, binding(p, "chat-model", LlmPurpose.CHAT, 0));
            stubProviders(p);
            stubRegistry(p);
            when(llmProviderService.decryptApiKey(p)).thenReturn("sk-key");

            ResolvedLlm resolved = resolver.resolveFor(agentId, userId, LlmPurpose.ROUTINE);

            assertEquals("cheap-model", resolved.model());
            assertFalse(resolved.platformFallback());
            verify(llmQuotaService).check(p, userId, agentId);
            verifyNoInteractions(platformLlmRepository);
        }

        @Test
        @DisplayName("списка ROUTINE нет — берётся модель CHAT агента")
        void fallsBackToChatModel() {
            LlmProvider p = provider("my-deepseek");
            p.setPurposePriority(Map.of(LlmPurpose.CHAT, List.of("chat-model")));
            stubNoChain(LlmPurpose.ROUTINE);
            stubChain(LlmPurpose.CHAT, binding(p, "chat-model", LlmPurpose.CHAT, 0));
            stubProviders(p);
            when(llmProviderModelRepository.findByLlmProviderIdAndModel(p.getId(), "chat-model"))
                    .thenReturn(Optional.empty());
            when(llmProviderService.decryptApiKey(p)).thenReturn("sk-key");

            ResolvedLlm resolved = resolver.resolveFor(agentId, userId, LlmPurpose.ROUTINE);

            assertEquals("chat-model", resolved.model());
            assertEquals(p, resolved.provider());
        }

        @Test
        @DisplayName("пустой ROUTINE-список не мешает фолбэку на CHAT")
        void emptyRoutineListStillFallsBackToChat() {
            LlmProvider p = provider("my-deepseek");
            p.setPurposePriority(Map.of(
                    LlmPurpose.ROUTINE, List.of(),
                    LlmPurpose.CHAT, List.of("chat-model")));
            stubNoChain(LlmPurpose.ROUTINE);
            stubChain(LlmPurpose.CHAT, binding(p, "chat-model", LlmPurpose.CHAT, 0));
            stubProviders(p);
            when(llmProviderModelRepository.findByLlmProviderIdAndModel(p.getId(), "chat-model"))
                    .thenReturn(Optional.empty());
            when(llmProviderService.decryptApiKey(p)).thenReturn("sk-key");

            assertEquals("chat-model", resolver.resolveFor(agentId, userId, LlmPurpose.ROUTINE).model());
        }

        @Test
        @DisplayName("за BYOK-агентом платформа не подставляется")
        void platformNotSubstitutedBehindByok() {
            LlmProvider p = provider("my-deepseek");
            p.setPurposePriority(Map.of(LlmPurpose.CHAT, List.of("retired-chat")));
            stubNoChain(LlmPurpose.ROUTINE);
            stubChain(LlmPurpose.CHAT, binding(p, "retired-chat", LlmPurpose.CHAT, 0));
            stubProviders(p);
            when(llmProviderModelRepository.findByLlmProviderIdAndModel(p.getId(), "retired-chat"))
                    .thenReturn(Optional.of(model("retired-chat", List.of("text"), List.of("text"),
                            LlmProviderModelStatus.UNAVAILABLE)));

            assertThrows(NotFoundStatusException.class,
                    () -> resolver.resolveFor(agentId, userId, LlmPurpose.ROUTINE));
            verifyNoInteractions(platformLlmRepository);
        }

        @Test
        @DisplayName("PLATFORM-агент: цепочка platform_llms[ROUTINE]")
        void platformRoutineChain() {
            platformAgent();
            LlmProvider platform = platformProvider();
            stubPlatformChain(LlmPurpose.ROUTINE,
                    platformBinding(platform, "cheap-model", LlmPurpose.ROUTINE, 0));
            stubNoPlatformChain(LlmPurpose.CHAT);
            stubProviders(platform);
            when(llmProviderService.decryptApiKey(platform)).thenReturn("sk-platform");

            ResolvedLlm resolved = resolver.resolveFor(agentId, userId, LlmPurpose.ROUTINE);

            assertEquals("cheap-model", resolved.model());
            assertTrue(resolved.platformFallback());
            verifyNoInteractions(agentLlmRepository);
        }

        @Test
        @DisplayName("PLATFORM-агент без ROUTINE-цепочки — platform_llms[CHAT]")
        void platformRoutineFallsBackToChatChain() {
            agentSource = LlmSource.PLATFORM;
            LlmProvider platform = platformProvider();
            stubNoPlatformChain(LlmPurpose.ROUTINE);
            stubPlatformChain(LlmPurpose.CHAT,
                    platformBinding(platform, "chat-model", LlmPurpose.CHAT, 0));
            stubProviders(platform);
            when(llmProviderService.decryptApiKey(platform)).thenReturn("sk-platform");

            assertEquals("chat-model", resolver.resolveFor(agentId, userId, LlmPurpose.ROUTINE).model());
        }
    }

    // ---- медиа -----------------------------------------------------------------------------

    @Nested
    @DisplayName("IMAGE/VISION — модель с нужной модальностью")
    class Media {

        @Test
        @DisplayName("явная цепочка: модель без image в output_modalities пропускается")
        void skipsModelWithoutImageOutput() {
            LlmProvider p = provider("mine");
            stubChain(LlmPurpose.IMAGE,
                    binding(p, "text-only", LlmPurpose.IMAGE, 0),
                    binding(p, "painter", LlmPurpose.IMAGE, 1));
            stubProviders(p);
            stubRow(p, textModel("text-only"));
            stubRow(p, alive("painter", List.of("text"), List.of("image")));
            when(llmProviderService.decryptApiKey(p)).thenReturn("sk-key");

            assertEquals("painter", resolver.resolveFor(agentId, userId, LlmPurpose.IMAGE).model());
        }

        @Test
        @DisplayName("VISION требует image во входных модальностях")
        void visionRequiresImageInput() {
            LlmProvider p = provider("mine");
            stubChain(LlmPurpose.VISION,
                    binding(p, "text-only", LlmPurpose.VISION, 0),
                    binding(p, "sees", LlmPurpose.VISION, 1));
            stubProviders(p);
            stubRow(p, textModel("text-only"));
            stubRow(p, alive("sees", List.of("text", "image"), List.of("text")));
            when(llmProviderService.decryptApiKey(p)).thenReturn("sk-key");

            assertEquals("sees", resolver.resolveFor(agentId, userId, LlmPurpose.VISION).model());
        }

        @Test
        @DisplayName("модели нет в реестре вовсе → берётся как есть: настройка до первого refresh законна")
        void unknownModelPasses() {
            LlmProvider p = provider("mine");
            stubChain(LlmPurpose.IMAGE, binding(p, "hand-entered", LlmPurpose.IMAGE, 0));
            stubProviders(p);
            when(llmProviderModelRepository.findByLlmProviderIdAndModel(p.getId(), "hand-entered"))
                    .thenReturn(Optional.empty());
            when(llmProviderService.decryptApiKey(p)).thenReturn("sk-key");

            assertEquals("hand-entered", resolver.resolveFor(agentId, userId, LlmPurpose.IMAGE).model());
        }

        @Test
        @DisplayName("нет привязок назначения → списки назначения у провайдеров чата")
        void fallsBackToChatProviderLists() {
            LlmProvider p = provider("my-openrouter");
            p.setPurposePriority(Map.of(LlmPurpose.IMAGE, List.of("first-image", "second-image")));
            stubNoChain(LlmPurpose.IMAGE);
            stubChain(LlmPurpose.CHAT, binding(p, "chat-model", LlmPurpose.CHAT, 0));
            stubProviders(p);
            stubRegistry(p,
                    alive("first-image", List.of("text"), List.of("image")),
                    alive("second-image", List.of("text"), List.of("image")));
            when(llmProviderService.decryptApiKey(p)).thenReturn("sk-key");

            ResolvedLlm resolved = resolver.resolveFor(agentId, userId, LlmPurpose.IMAGE);

            assertEquals("first-image", resolved.model());
            assertFalse(resolved.platformFallback());
        }

        @Test
        @DisplayName("модель без нужной модальности в списке провайдера пропускается")
        void skipsWrongModalityInProviderList() {
            LlmProvider p = provider("mine");
            p.setPurposePriority(Map.of(LlmPurpose.IMAGE, List.of("chat-model", "painter")));
            stubNoChain(LlmPurpose.IMAGE);
            stubChain(LlmPurpose.CHAT, binding(p, "chat-model", LlmPurpose.CHAT, 0));
            stubProviders(p);
            stubRegistry(p, textModel("chat-model"), alive("painter", List.of("text"), List.of("image")));
            when(llmProviderService.decryptApiKey(p)).thenReturn("sk-key");

            assertEquals("painter", resolver.resolveFor(agentId, userId, LlmPurpose.IMAGE).model());
        }

        @Test
        @DisplayName("пустой список у своего провайдера выпадает из цепочки, а не останавливает её")
        void emptyListSkipsToTheNextOwnProvider() {
            LlmProvider first = provider("first");
            first.setPurposePriority(Map.of(LlmPurpose.IMAGE, List.of()));
            LlmProvider second = provider("second");
            second.setPurposePriority(Map.of(LlmPurpose.IMAGE, List.of("painter")));
            stubNoChain(LlmPurpose.IMAGE);
            stubChain(LlmPurpose.CHAT,
                    binding(first, "chat-1", LlmPurpose.CHAT, 0),
                    binding(second, "chat-2", LlmPurpose.CHAT, 1));
            stubProviders(first, second);
            stubRegistry(second, alive("painter", List.of("text"), List.of("image")));
            when(llmProviderService.decryptApiKey(second)).thenReturn("sk-key");

            assertEquals("painter", resolver.resolveFor(agentId, userId, LlmPurpose.IMAGE).model());
        }

        @Test
        @DisplayName("PLATFORM-агент: только platform_llms[IMAGE], CHAT-привязки не читаются")
        void platformMediaChain() {
            platformAgent();
            LlmProvider platform = platformProvider();
            stubPlatformChain(LlmPurpose.IMAGE,
                    platformBinding(platform, "painter", LlmPurpose.IMAGE, 0));
            stubProviders(platform);
            stubRow(platform, alive("painter", List.of("text"), List.of("image")));
            when(llmProviderService.decryptApiKey(platform)).thenReturn("sk-platform");

            ResolvedLlm resolved = resolver.resolveFor(agentId, userId, LlmPurpose.IMAGE);

            assertEquals("painter", resolved.model());
            assertTrue(resolved.platformFallback());
            verifyNoInteractions(agentLlmRepository);
        }

        @Test
        @DisplayName("пустая платформенная медиа-цепочка не уходит в CHAT")
        void platformMediaDoesNotFallBackToChat() {
            platformAgent();
            stubNoPlatformChain(LlmPurpose.IMAGE);

            NoCapableModelException e = assertThrows(NoCapableModelException.class,
                    () -> resolver.resolveFor(agentId, userId, LlmPurpose.IMAGE));
            assertTrue(e.getMessage().contains("declares no IMAGE models"), e.getMessage());
            assertTrue(e.getMessage().contains("platform admin"), e.getMessage());
            assertFalse(e.getMessage().contains("Bind a IMAGE model"), e.getMessage());
            verify(llmProviderService, never()).decryptApiKey(any());
        }

        @Test
        @DisplayName("BYOK без медиа-моделей → текст предлагает переключить агента на платформу")
        void byokMediaHintSuggestsPlatform() {
            stubNoChain(LlmPurpose.IMAGE);
            stubNoChain(LlmPurpose.CHAT);

            NoCapableModelException e = assertThrows(NoCapableModelException.class,
                    () -> resolver.resolveFor(agentId, userId, LlmPurpose.IMAGE));

            assertTrue(e.getMessage().contains("Bind a IMAGE model"), e.getMessage());
            assertTrue(e.getMessage().contains("IMAGE list"), e.getMessage());
            assertTrue(e.getMessage().contains("switch the agent to the platform"), e.getMessage());
        }

        @Test
        @DisplayName("явные медиа-привязки закрыты: платформа не подставляется")
        void explicitMediaChainNeverReachesPlatform() {
            LlmProvider p = provider("mine");
            stubChain(LlmPurpose.IMAGE, binding(p, "text-only", LlmPurpose.IMAGE, 0));
            stubProviders(p);
            stubRow(p, textModel("text-only"));

            assertThrows(NoCapableModelException.class,
                    () -> resolver.resolveFor(agentId, userId, LlmPurpose.IMAGE));
            verifyNoInteractions(platformLlmRepository);
        }
    }

    // ---- extra_body ------------------------------------------------------------------------

    @Nested
    @DisplayName("дефолты из реестра — max_tokens под обоими уровнями extra_body")
    class RegistryDefaults {

        private static LlmProviderModel row(Integer maxOut, Integer context, List<String> supported) {
            return LlmProviderModel.builder()
                    .model("m")
                    .maxOutputTokens(maxOut)
                    .contextWindow(context)
                    .supportedParameters(supported)
                    .status(LlmProviderModelStatus.AVAILABLE)
                    .build();
        }

        @Test
        @DisplayName("лимит модели ниже потолка и половины окна уходит как есть")
        void smallLimitAsIs() {
            assertEquals(Map.of("max_tokens", 8192),
                    LlmCredentialsResolver.registryDefaults(row(8192, 131072, List.of("max_tokens"))));
        }

        @Test
        @DisplayName("огромный лимит листинга срезается потолком")
        void cappedAtCeiling() {
            assertEquals(Map.of("max_tokens", LlmCredentialsResolver.MAX_TOKENS_CAP),
                    LlmCredentialsResolver.registryDefaults(row(384000, 1048576, List.of("max_tokens"))));
        }

        @Test
        @DisplayName("лимит, равный окну, срезается до половины окна — вторая половина под промпт")
        void halfOfContext() {
            assertEquals(Map.of("max_tokens", 16384),
                    LlmCredentialsResolver.registryDefaults(row(32768, 32768, List.of("max_tokens"))));
        }

        @Test
        @DisplayName("модель знает только max_completion_tokens → ключ берётся он")
        void completionTokensKey() {
            assertEquals(Map.of("max_completion_tokens", 8192), LlmCredentialsResolver.registryDefaults(
                    row(8192, null, List.of("max_completion_tokens", "tools"))));
        }

        @Test
        @DisplayName("нет строки, лимита или списка параметров — ничего не шлём")
        void unknownSendsNothing() {
            assertTrue(LlmCredentialsResolver.registryDefaults(null).isEmpty());
            assertTrue(LlmCredentialsResolver.registryDefaults(row(null, 1000, List.of("max_tokens"))).isEmpty());
            assertTrue(LlmCredentialsResolver.registryDefaults(row(8192, 131072, null)).isEmpty());
            assertTrue(LlmCredentialsResolver.registryDefaults(row(8192, 131072, List.of("tools"))).isEmpty());
        }

        private LlmProvider bindChat(Map<String, Object> providerExtra, LlmProviderModel row) {
            LlmProvider bound = provider("my-openrouter");
            bound.setExtraBody(providerExtra);
            stubChain(LlmPurpose.CHAT, binding(bound, "m", LlmPurpose.CHAT, 0));
            stubProviders(bound);
            when(llmProviderService.decryptApiKey(bound)).thenReturn("sk-key");
            when(llmProviderModelRepository.findByLlmProviderIdAndModel(bound.getId(), "m"))
                    .thenReturn(Optional.of(row));
            return bound;
        }

        @Test
        @DisplayName("resolveFor(CHAT) кладёт дефолт под extra_body провайдера")
        void chatGetsDefault() {
            bindChat(Map.of("provider", Map.of("sort", "throughput")),
                    row(8192, 131072, List.of("max_tokens")));

            ResolvedLlm resolved = resolver.resolveFor(agentId, userId, LlmPurpose.CHAT);

            assertEquals(Map.of("max_tokens", 8192, "provider", Map.of("sort", "throughput")),
                    resolved.extraBody());
        }

        @Test
        @DisplayName("extra_body провайдера и модели перебивают дефолт, null модели его убирает")
        void extraBodyOverrides() {
            LlmProviderModel model = row(8192, 131072, List.of("max_tokens"));
            Map<String, Object> modelExtra = new java.util.HashMap<>();
            modelExtra.put("max_tokens", null);
            model.setExtraBody(modelExtra);
            bindChat(Map.of("max_tokens", 64000), model);

            assertFalse(resolver.resolveFor(agentId, userId, LlmPurpose.CHAT).extraBody()
                    .containsKey("max_tokens"));

            model.setExtraBody(Map.of("max_tokens", 100000));
            assertEquals(100000, resolver.resolveFor(agentId, userId, LlmPurpose.CHAT)
                    .extraBody().get("max_tokens"));
        }

        @Test
        @DisplayName("медиа-назначения дефолт не получают — их тело может уйти не в chat/completions")
        void mediaWithoutDefault() {
            LlmProvider p = provider("my-openrouter");
            stubChain(LlmPurpose.IMAGE, binding(p, "m", LlmPurpose.IMAGE, 0));
            stubProviders(p);
            when(llmProviderService.decryptApiKey(p)).thenReturn("sk-key");
            when(llmProviderModelRepository.findByLlmProviderIdAndModel(p.getId(), "m"))
                    .thenReturn(Optional.of(row(8192, 131072, List.of("max_tokens"))));

            assertTrue(resolver.resolveFor(agentId, userId, LlmPurpose.IMAGE).extraBody().isEmpty());
        }
    }
}
