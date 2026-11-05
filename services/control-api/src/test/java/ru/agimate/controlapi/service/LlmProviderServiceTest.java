package ru.agimate.controlapi.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.ConflictStatusException;
import ru.agimate.controlapi.controller.manage.dto.llm.CreatePlatformLlmProviderRequest;
import ru.agimate.controlapi.controller.manage.dto.llm.UpdateLlmProviderRequest;
import ru.agimate.controlapi.database.entities.LlmModelDefaults;
import ru.agimate.controlapi.database.entities.LlmProvider;
import ru.agimate.controlapi.database.entities.LlmProviderModel;
import ru.agimate.controlapi.database.entities.Secret;
import ru.agimate.controlapi.database.enums.LlmProviderModelStatus;
import ru.agimate.controlapi.database.enums.LlmProviderType;
import ru.agimate.controlapi.database.enums.LlmPurpose;
import ru.agimate.controlapi.database.model.LlmModelInfo;
import ru.agimate.controlapi.database.repositories.LlmModelDefaultsRepository;
import ru.agimate.controlapi.database.repositories.LlmProviderModelRepository;
import ru.agimate.controlapi.database.repositories.LlmProviderRepository;
import ru.agimate.controlapi.database.repositories.PlatformLlmRepository;
import ru.agimate.controlapi.database.repositories.SecretRepository;
import ru.agimate.controlapi.service.llm.discovery.LlmModelDiscoveryService;
import ru.agimate.controlapi.service.secret.SecretService;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("LlmProviderService — платформенные провайдеры")
class LlmProviderServiceTest {

    private static final String BASE_URL = "https://openrouter.ai/api/v1";
    private static final String API_KEY = "sk-or-platform-key-123456";
    private static final String PLATFORM_NAME = "platform";

    @Mock
    private LlmProviderRepository llmProviderRepository;
    @Mock
    private PlatformLlmRepository platformLlmRepository;
    @Mock
    private LlmProviderModelRepository llmProviderModelRepository;
    @Mock
    private LlmModelDefaultsRepository llmModelDefaultsRepository;
    @Mock
    private SecretRepository secretRepository;
    @Mock
    private SecretService secretService;
    @Mock
    private LlmModelDiscoveryService modelDiscoveryService;

    @InjectMocks
    private LlmProviderService service;

    @Nested
    @DisplayName("createPlatformProvider")
    class CreatePlatform {

        @Test
        @DisplayName("создаёт под SYSTEM_USER_ID с принятым именем, выключенным и без purpose_priority")
        void createsNamedDisabled() {
            when(llmProviderRepository.existsByUserIdAndName(
                    SystemSkillBootstrap.SYSTEM_USER_ID, PLATFORM_NAME)).thenReturn(false);
            when(llmProviderRepository.save(any())).thenAnswer(inv -> {
                LlmProvider p = inv.getArgument(0);
                if (p.getId() == null) {
                    p.setId(UUID.randomUUID());
                }
                return p;
            });
            Secret secret = secretWithId(UUID.randomUUID());
            when(secretService.store(eq("llm_provider"), any(), eq(Map.of("api_key", API_KEY))))
                    .thenReturn(secret);

            var response = service.createPlatformProvider(
                    new CreatePlatformLlmProviderRequest(
                            PLATFORM_NAME, LlmProviderType.OPENAI_COMPATIBLE, BASE_URL, API_KEY));

            assertEquals(PLATFORM_NAME, response.name());
            assertTrue(response.platform());
            assertFalse(response.enabled(), "создаётся выключенным — включение после настройки квот");
            assertNull(response.purposePriority(), "назначения живут в platform_llms, не в провайдере");
        }

        @Test
        @DisplayName("имя занято среди платформенных — 409")
        void rejectsDuplicate() {
            when(llmProviderRepository.existsByUserIdAndName(
                    SystemSkillBootstrap.SYSTEM_USER_ID, PLATFORM_NAME)).thenReturn(true);

            assertThrows(ConflictStatusException.class,
                    () -> service.createPlatformProvider(
                            new CreatePlatformLlmProviderRequest(
                                    PLATFORM_NAME, LlmProviderType.OPENAI_COMPATIBLE, BASE_URL, API_KEY)));
            verify(llmProviderRepository, never()).save(any());
        }

        @Test
        @DisplayName("второе имя рядом с platform разрешено: платформа не синглтон")
        void allowsSecondPlatformProvider() {
            when(llmProviderRepository.existsByUserIdAndName(
                    SystemSkillBootstrap.SYSTEM_USER_ID, "platform-images")).thenReturn(false);
            when(llmProviderRepository.save(any())).thenAnswer(inv -> {
                LlmProvider p = inv.getArgument(0);
                if (p.getId() == null) {
                    p.setId(UUID.randomUUID());
                }
                return p;
            });
            when(secretService.store(eq("llm_provider"), any(), any()))
                    .thenReturn(secretWithId(UUID.randomUUID()));

            var response = service.createPlatformProvider(
                    new CreatePlatformLlmProviderRequest(
                            "platform-images", LlmProviderType.OPENAI_COMPATIBLE, BASE_URL, API_KEY));

            assertEquals("platform-images", response.name());
            assertTrue(response.platform());
        }
    }

    @Nested
    @DisplayName("admin-доступ к платформенным провайдерам")
    class AdminAccess {

        private final UUID adminId = UUID.randomUUID();
        private final UUID strangerId = UUID.randomUUID();

        @Test
        @DisplayName("админ получает платформенную строку; не-админ — 404")
        void adminReachesPlatformRow() {
            LlmProvider platform = existingProvider(true);
            when(llmProviderRepository.findById(platform.getId())).thenReturn(Optional.of(platform));

            assertEquals(platform,
                    service.requireOwnedOrPlatformAdmin(platform.getId(), adminId, true));

            when(llmProviderRepository.findByIdAndUserId(platform.getId(), strangerId))
                    .thenReturn(Optional.empty());
            assertThrows(ru.agimate.common.rest.error.NotFoundStatusException.class,
                    () -> service.requireOwnedOrPlatformAdmin(platform.getId(), strangerId, false));
        }

        @Test
        @DisplayName("чужая пользовательская строка для админа — 404 (только своя или платформенная)")
        void adminCannotReachForeignUserRow() {
            LlmProvider foreign = existingProvider(true);
            foreign.setUserId(strangerId);
            foreign.setName("someones-openai");
            when(llmProviderRepository.findById(foreign.getId())).thenReturn(Optional.of(foreign));

            assertThrows(ru.agimate.common.rest.error.NotFoundStatusException.class,
                    () -> service.requireOwnedOrPlatformAdmin(foreign.getId(), adminId, true));
        }

        @Test
        @DisplayName("листинг админа показывает все платформенные строки, не-админу — ни одной")
        void adminListIncludesEveryPlatformRow() {
            LlmProvider first = existingProvider(true);
            LlmProvider second = existingProvider(true);
            second.setId(UUID.randomUUID());
            second.setName("platform-images");
            when(llmProviderRepository.findAllByUserIdOrderByCreatedAtDesc(adminId)).thenReturn(List.of());
            when(llmProviderRepository.findAllByUserIdOrderByCreatedAtDesc(
                    SystemSkillBootstrap.SYSTEM_USER_ID)).thenReturn(List.of(first, second));

            var list = service.listForUser(adminId, true);

            assertEquals(2, list.size());
            assertTrue(list.stream().allMatch(r -> r.platform()));
            assertTrue(service.listForUser(adminId, false).isEmpty(),
                    "без роли ADMIN платформенные строки не видны");
        }

        @Test
        @DisplayName("платформенную строку можно переименовать; уникальность проверяется по её владельцу")
        void platformRenameAllowed() {
            LlmProvider platform = existingProvider(true);
            when(llmProviderRepository.findById(platform.getId())).thenReturn(Optional.of(platform));
            when(llmProviderRepository.existsByUserIdAndName(
                    SystemSkillBootstrap.SYSTEM_USER_ID, "platform-2")).thenReturn(false);
            when(llmProviderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var response = service.update(platform.getId(), adminId, true,
                    new UpdateLlmProviderRequest("platform-2", null, null, null, null, null, null, null));

            assertEquals("platform-2", response.name());
        }

        @Test
        @DisplayName("удаление платформенного провайдера без platform_llms разрешено")
        void platformDeleteAllowedWithoutPurposes() {
            LlmProvider platform = existingProvider(true);
            when(llmProviderRepository.findById(platform.getId())).thenReturn(Optional.of(platform));
            when(platformLlmRepository.existsByLlmProviderId(platform.getId())).thenReturn(false);

            service.delete(platform.getId(), adminId, true);

            verify(llmProviderRepository).delete(platform);
            verify(secretRepository).deleteById(platform.getSecretId());
        }

        @Test
        @DisplayName("удаление провайдера, на который ссылаются назначения, — 400")
        void platformDeleteRejectedWhileReferenced() {
            LlmProvider platform = existingProvider(true);
            when(llmProviderRepository.findById(platform.getId())).thenReturn(Optional.of(platform));
            when(platformLlmRepository.existsByLlmProviderId(platform.getId())).thenReturn(true);

            assertThrows(BadRequestStatusException.class,
                    () -> service.delete(platform.getId(), adminId, true));
            verify(llmProviderRepository, never()).delete(any());
            verify(secretRepository, never()).deleteById(any());
        }

        @Test
        @DisplayName("FK-гонка: ссылка появилась после проверки — 400, секрет не удалён")
        void platformDeleteMapsForeignKeyRaceToBadRequest() {
            LlmProvider platform = existingProvider(true);
            when(llmProviderRepository.findById(platform.getId())).thenReturn(Optional.of(platform));
            when(platformLlmRepository.existsByLlmProviderId(platform.getId())).thenReturn(false);
            org.mockito.Mockito.doThrow(new org.springframework.dao.DataIntegrityViolationException("fk"))
                    .when(llmProviderRepository).flush();

            assertThrows(BadRequestStatusException.class,
                    () -> service.delete(platform.getId(), adminId, true));
            verify(secretRepository, never()).deleteById(any());
        }

        @Test
        @DisplayName("purpose_priority платформенному провайдеру запрещён — 400")
        void platformPurposePriorityRejected() {
            LlmProvider platform = existingProvider(true);
            when(llmProviderRepository.findById(platform.getId())).thenReturn(Optional.of(platform));

            assertThrows(BadRequestStatusException.class,
                    () -> service.update(platform.getId(), adminId, true,
                            new UpdateLlmProviderRequest(null, null, null, null,
                                    Map.of(LlmPurpose.CHAT, List.of("gemini-flash")), null, null, null)));
            verify(llmProviderRepository, never()).save(any());
        }

        @Test
        @DisplayName("enabled по-прежнему задаётся через update")
        void adminEnables() {
            LlmProvider platform = existingProvider(false);
            when(llmProviderRepository.findById(platform.getId())).thenReturn(Optional.of(platform));
            when(llmProviderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var response = service.update(platform.getId(), adminId, true,
                    new UpdateLlmProviderRequest(null, null, null, null, null, null, null, true));

            assertTrue(response.enabled());
            assertTrue(response.platform());
            assertNull(response.purposePriority(),
                    "purpose_priority платформенной строки не читается и не отдаётся");
        }

        @Test
        @DisplayName("media_transport задаётся через update: у OpenRouter и Polza один providerType")
        void updatesMediaTransport() {
            LlmProvider platform = existingProvider(false);
            when(llmProviderRepository.findById(platform.getId())).thenReturn(Optional.of(platform));
            when(llmProviderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var response = service.update(platform.getId(), adminId, true,
                    new UpdateLlmProviderRequest(
                            null, null, null, null, null, null,
                            ru.agimate.controlapi.database.enums.MediaTransportType.MEDIA_ENDPOINT, null));

            assertEquals(ru.agimate.controlapi.database.enums.MediaTransportType.MEDIA_ENDPOINT,
                    response.mediaTransport());
        }
    }

    @Nested
    @DisplayName("providerType — смена типа через PATCH")
    class UpdateProviderType {

        private final UUID userId = UUID.randomUUID();

        private LlmProvider owned(LlmProviderType type, String baseUrl) {
            LlmProvider provider = LlmProvider.builder()
                    .id(UUID.randomUUID()).userId(userId).name("moonshot")
                    .providerType(type).baseUrl(baseUrl).enabled(true).build();
            when(llmProviderRepository.findByIdAndUserId(provider.getId(), userId))
                    .thenReturn(Optional.of(provider));
            return provider;
        }

        @Test
        @DisplayName("OPENAI → OPENAI_COMPATIBLE вместе с base_url")
        void switchesTypeAndBaseUrl() {
            LlmProvider provider = owned(LlmProviderType.OPENAI, "https://api.moonshot.ai");
            when(llmProviderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var response = service.update(provider.getId(), userId, false,
                    new UpdateLlmProviderRequest(null, LlmProviderType.OPENAI_COMPATIBLE,
                            "https://api.moonshot.ai/v1", null, null, null, null, null));

            assertEquals(LlmProviderType.OPENAI_COMPATIBLE, response.providerType());
            assertEquals("https://api.moonshot.ai/v1", response.baseUrl());
        }

        @Test
        @DisplayName("смена на OPENAI_COMPATIBLE без base_url — 400")
        void switchingToCompatibleRequiresBaseUrl() {
            LlmProvider provider = owned(LlmProviderType.OPENAI, null);

            assertThrows(BadRequestStatusException.class, () -> service.update(
                    provider.getId(), userId, false,
                    new UpdateLlmProviderRequest(null, LlmProviderType.OPENAI_COMPATIBLE,
                            null, null, null, null, null, null)));
            verify(llmProviderRepository, never()).save(any());
        }

        @Test
        @DisplayName("null — тип не меняется")
        void nullKeepsCurrentType() {
            LlmProvider provider = owned(LlmProviderType.OPENAI, null);
            when(llmProviderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var response = service.update(provider.getId(), userId, false,
                    new UpdateLlmProviderRequest("renamed", null, null, null, null, null, null, null));

            assertEquals(LlmProviderType.OPENAI, response.providerType());
            assertEquals("renamed", response.name());
        }

        @Test
        @DisplayName("OPENAI_COMPATIBLE → OPENAI: base_url больше не обязателен")
        void switchingToOpenAiDropsBaseRequirement() {
            LlmProvider provider = owned(LlmProviderType.OPENAI_COMPATIBLE, "https://api.moonshot.ai/v1");
            when(llmProviderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var response = service.update(provider.getId(), userId, false,
                    new UpdateLlmProviderRequest(null, LlmProviderType.OPENAI,
                            null, null, null, null, null, null));

            assertEquals(LlmProviderType.OPENAI, response.providerType());
        }
    }

    @Nested
    @DisplayName("validateModelsKnown")
    class ValidateModelsKnown {

        private final UUID userId = UUID.randomUUID();

        @Test
        @DisplayName("модели нет в реестре → 400: опечатка ловится на записи, не на вызове")
        void unknownModelIsRejected() {
            LlmProvider provider = provider();
            when(llmProviderModelRepository.findAllByLlmProviderIdOrderByModel(provider.getId()))
                    .thenReturn(List.of(LlmProviderModel.builder().model("known").build()));

            assertThrows(BadRequestStatusException.class,
                    () -> service.validateModelsKnown(provider, List.of("typo")));
        }

        @Test
        @DisplayName("реестр пуст → всё разрешено: discovery ещё не запускался")
        void emptyRegistryAllowsEverything() {
            LlmProvider provider = provider();
            when(llmProviderModelRepository.findAllByLlmProviderIdOrderByModel(provider.getId()))
                    .thenReturn(List.of());

            service.validateModelsKnown(provider, List.of("anything"));
        }

        @Test
        @DisplayName("UNAVAILABLE-строка проходит: выпавшую из листинга модель назвать снова можно")
        void unavailableRowStaysAllowed() {
            LlmProvider provider = provider();
            when(llmProviderModelRepository.findAllByLlmProviderIdOrderByModel(provider.getId()))
                    .thenReturn(List.of(LlmProviderModel.builder()
                            .model("gone").status(LlmProviderModelStatus.UNAVAILABLE).build()));

            service.validateModelsKnown(provider, List.of("gone"));
        }

        private LlmProvider provider() {
            return LlmProvider.builder()
                    .id(UUID.randomUUID()).userId(userId).name("my-openai")
                    .providerType(LlmProviderType.OPENAI).enabled(true).build();
        }
    }

    @Nested
    @DisplayName("BYOK — форма purpose_priority")
    class ByokPurposeShape {

        private final UUID userId = UUID.randomUUID();

        private LlmProvider ownedProvider() {
            LlmProvider provider = LlmProvider.builder()
                    .id(UUID.randomUUID()).userId(userId).name("my-openai")
                    .providerType(LlmProviderType.OPENAI).enabled(true).build();
            when(llmProviderRepository.findByIdAndUserId(provider.getId(), userId))
                    .thenReturn(Optional.of(provider));
            return provider;
        }

        @Test
        @DisplayName("пустая строка в списке — 400")
        void blankModelRejected() {
            LlmProvider provider = ownedProvider();

            assertThrows(BadRequestStatusException.class, () -> service.update(
                    provider.getId(), userId, false,
                    new UpdateLlmProviderRequest(null, null, null, null,
                            Map.of(LlmPurpose.CHAT, List.of(" ")), null, null, null)));
            verify(llmProviderRepository, never()).save(any());
        }

        @Test
        @DisplayName("дубль в списке — 400")
        void duplicateModelRejected() {
            LlmProvider provider = ownedProvider();

            assertThrows(BadRequestStatusException.class, () -> service.update(
                    provider.getId(), userId, false,
                    new UpdateLlmProviderRequest(null, null, null, null,
                            Map.of(LlmPurpose.CHAT, List.of("m", "m")), null, null, null)));
            verify(llmProviderRepository, never()).save(any());
        }

        @Test
        @DisplayName("null вместо списка — 400: [] уже значит «выключено»")
        void nullListRejected() {
            LlmProvider provider = ownedProvider();
            Map<LlmPurpose, List<String>> purposePriority = new java.util.HashMap<>();
            purposePriority.put(LlmPurpose.CHAT, null);

            assertThrows(BadRequestStatusException.class, () -> service.update(
                    provider.getId(), userId, false,
                    new UpdateLlmProviderRequest(null, null, null, null,
                            purposePriority, null, null, null)));
            verify(llmProviderRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("refreshModels — upsert-реестр")
    class RefreshModels {

        private final UUID adminId = UUID.randomUUID();
        private LlmProvider provider;

        private void mockProviderWithKey() {
            provider = existingProvider(true);
            when(llmProviderRepository.findById(provider.getId())).thenReturn(Optional.of(provider));
            Secret secret = secretWithId(provider.getSecretId());
            when(secretRepository.findById(provider.getSecretId())).thenReturn(Optional.of(secret));
            when(secretService.reveal(secret, provider.getId())).thenReturn(Map.of("api_key", API_KEY));
        }

        @Test
        @DisplayName("новая модель из листинга → строка AVAILABLE с first/last_seen и метаданными")
        void insertsNewModel() {
            mockProviderWithKey();
            when(modelDiscoveryService.discover(eq(provider), any())).thenReturn(List.of(
                    new LlmModelInfo("moonshotai/kimi-k2.5", "Kimi K2.5", 262144, 8192,
                            List.of("text", "image"), List.of("text"), List.of("tools"), null)));
            when(llmProviderModelRepository.findAllByLlmProviderIdOrderByModel(provider.getId()))
                    .thenReturn(List.of());
            when(llmProviderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.refreshModels(provider.getId(), adminId, true);

            ArgumentCaptor<List<LlmProviderModel>> captor = ArgumentCaptor.forClass(List.class);
            verify(llmProviderModelRepository).saveAll(captor.capture());
            LlmProviderModel row = captor.getValue().get(0);
            assertEquals("moonshotai/kimi-k2.5", row.getModel());
            assertEquals(LlmProviderModelStatus.AVAILABLE, row.getStatus());
            assertEquals(262144, row.getContextWindow());
            assertEquals(8192, row.getMaxOutputTokens());
            assertEquals(List.of("text", "image"), row.getInputModalities());
            assertEquals(List.of("text"), row.getOutputModalities());
            assertTrue(row.getFirstSeenAt() != null && row.getLastSeenAt() != null);
        }

        @Test
        @DisplayName("write-time оверлей: null-поля добираются из defaults, discovered побеждает")
        void appliesDefaultsToDiscoveryGaps() {
            mockProviderWithKey();
            // Провайдер отдал только id + context, модальности/output не знает.
            when(modelDiscoveryService.discover(eq(provider), any())).thenReturn(List.of(
                    new LlmModelInfo("whisper-1", null, 4096, null, null, null, null, null)));
            when(llmProviderModelRepository.findAllByLlmProviderIdOrderByModel(provider.getId()))
                    .thenReturn(List.of());
            when(llmModelDefaultsRepository.findByModelIn(List.of("whisper-1"))).thenReturn(List.of(
                    LlmModelDefaults.builder()
                            .model("whisper-1")
                            .contextWindow(999) // discovered 4096 должен победить
                            .inputModalities(List.of("audio"))
                            .outputModalities(List.of("text"))
                            .build()));
            when(llmProviderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.refreshModels(provider.getId(), adminId, true);

            ArgumentCaptor<List<LlmProviderModel>> captor = ArgumentCaptor.forClass(List.class);
            verify(llmProviderModelRepository).saveAll(captor.capture());
            LlmProviderModel row = captor.getValue().get(0);
            assertEquals(4096, row.getContextWindow(), "discovered побеждает default");
            assertEquals(List.of("audio"), row.getInputModalities(), "дырка добрана из default");
            assertEquals(List.of("text"), row.getOutputModalities(), "дырка добрана из default");
        }

        @Test
        @DisplayName("модель пропала из листинга → UNAVAILABLE, строка не удаляется")
        void marksDisappearedUnavailable() {
            mockProviderWithKey();
            LlmProviderModel gone = LlmProviderModel.builder()
                    .llmProviderId(provider.getId()).model("old-model")
                    .status(LlmProviderModelStatus.AVAILABLE).build();
            when(modelDiscoveryService.discover(eq(provider), any()))
                    .thenReturn(List.of(new LlmModelInfo("new-model", null)));
            when(llmProviderModelRepository.findAllByLlmProviderIdOrderByModel(provider.getId()))
                    .thenReturn(List.of(gone));
            when(llmProviderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.refreshModels(provider.getId(), adminId, true);

            assertEquals(LlmProviderModelStatus.UNAVAILABLE, gone.getStatus());
            verify(llmProviderModelRepository, never()).delete(any());
            verify(llmProviderModelRepository, never()).deleteAll(any());
        }

        @Test
        @DisplayName("вернувшаяся/сконфигуренная руками модель → AVAILABLE, first_seen проставляется")
        void revivesManualRow() {
            mockProviderWithKey();
            LlmProviderModel manual = LlmProviderModel.builder()
                    .llmProviderId(provider.getId()).model("moonshotai/kimi-k2.5")
                    .status(LlmProviderModelStatus.UNAVAILABLE)
                    .extraBody(Map.of("provider", Map.of("only", List.of("moonshotai"))))
                    .build();
            when(modelDiscoveryService.discover(eq(provider), any()))
                    .thenReturn(List.of(new LlmModelInfo("moonshotai/kimi-k2.5", null)));
            when(llmProviderModelRepository.findAllByLlmProviderIdOrderByModel(provider.getId()))
                    .thenReturn(List.of(manual));
            when(llmProviderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.refreshModels(provider.getId(), adminId, true);

            assertEquals(LlmProviderModelStatus.AVAILABLE, manual.getStatus());
            assertTrue(manual.getFirstSeenAt() != null, "конфиг заведён руками до появления в листинге");
            assertEquals(Map.of("provider", Map.of("only", List.of("moonshotai"))), manual.getExtraBody(),
                    "extra_body при refresh не трогается");
        }

        @Test
        @DisplayName("guard: пустой листинг — статусы не трогаем, refreshed_at не двигаем")
        void emptyListingLeavesRegistryUntouched() {
            mockProviderWithKey();
            when(modelDiscoveryService.discover(eq(provider), any())).thenReturn(List.of());

            service.refreshModels(provider.getId(), adminId, true);

            verify(llmProviderModelRepository, never()).saveAll(any());
            verify(llmProviderRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("upsertModelExtraBody")
    class UpsertExtraBody {

        private final UUID adminId = UUID.randomUUID();

        @Test
        @DisplayName("строки нет → создаётся UNAVAILABLE без first_seen (конфиг до refresh)")
        void createsRowForUnlistedModel() {
            LlmProvider provider = existingProvider(true);
            when(llmProviderRepository.findById(provider.getId())).thenReturn(Optional.of(provider));
            when(llmProviderModelRepository.findByLlmProviderIdAndModel(provider.getId(), "x/y"))
                    .thenReturn(Optional.empty());
            when(llmProviderModelRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var response = service.upsertModelExtraBody(provider.getId(), adminId, true,
                    new ru.agimate.controlapi.controller.manage.dto.llm.UpsertModelExtraBodyRequest(
                            "x/y", Map.of("transforms", List.of("middle-out"))));

            assertEquals("x/y", response.model());
            assertEquals(LlmProviderModelStatus.UNAVAILABLE, response.status());
            assertNull(response.firstSeenAt());
            assertEquals(Map.of("transforms", List.of("middle-out")), response.extraBody());
        }

        @Test
        @DisplayName("слишком большой extra_body — 400")
        void rejectsOversizedExtraBody() {
            LlmProvider provider = existingProvider(true);
            when(llmProviderRepository.findById(provider.getId())).thenReturn(Optional.of(provider));

            assertThrows(BadRequestStatusException.class,
                    () -> service.upsertModelExtraBody(provider.getId(), adminId, true,
                            new ru.agimate.controlapi.controller.manage.dto.llm.UpsertModelExtraBodyRequest(
                                    "x/y", Map.of("junk", "a".repeat(17 * 1024)))));
            verify(llmProviderModelRepository, never()).save(any());
        }
    }

    private static LlmProvider existingProvider(boolean enabled) {
        return LlmProvider.builder()
                .id(UUID.randomUUID())
                .userId(SystemSkillBootstrap.SYSTEM_USER_ID)
                .name(PLATFORM_NAME)
                .providerType(LlmProviderType.OPENAI_COMPATIBLE)
                .baseUrl(BASE_URL)
                .purposePriority(Map.of(LlmPurpose.CHAT, List.of("gpt-5-mini")))
                .secretId(UUID.randomUUID())
                .apiKeyMask("sk-****")
                .enabled(enabled)
                .build();
    }

    private static Secret secretWithId(UUID id) {
        Secret secret = new Secret();
        secret.setId(id);
        return secret;
    }
}
