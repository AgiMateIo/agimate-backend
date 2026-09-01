package ru.agimate.controlapi.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
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
import ru.agimate.controlapi.database.repositories.SecretRepository;
import ru.agimate.controlapi.service.llm.discovery.LlmModelDiscoveryService;
import ru.agimate.controlapi.service.secret.SecretService;
import ru.agimate.controlapi.service.http.PublicOnlyHttp;

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
@DisplayName("LlmProviderService — платформенный провайдер")
class LlmProviderServiceTest {

    private static final String BASE_URL = "https://openrouter.ai/api/v1";
    private static final String API_KEY = "sk-or-platform-key-123456";
    private static final String MODEL = "gpt-5-mini";

    @Mock
    private LlmProviderRepository llmProviderRepository;
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
    /** Настоящий, не мок: гард адреса — часть проверяемого здесь поведения, и он ничего не стоит. */
    @Spy
    private PublicOnlyHttp publicOnlyHttp = new PublicOnlyHttp(false);

    @InjectMocks
    private LlmProviderService service;

    @Nested
    @DisplayName("createPlatformProvider")
    class CreatePlatform {

        @Test
        @DisplayName("создаёт под SYSTEM_USER_ID с форсированным именем, выключенным")
        void createsForcedDisabled() {
            when(llmProviderRepository.findByUserIdAndName(
                    SystemSkillBootstrap.SYSTEM_USER_ID, LlmProviderService.PLATFORM_PROVIDER_NAME))
                    .thenReturn(Optional.empty());
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
                    new ru.agimate.controlapi.controller.manage.dto.llm.CreatePlatformLlmProviderRequest(
                            LlmProviderType.OPENAI_COMPATIBLE, BASE_URL, API_KEY,
                            Map.of(LlmPurpose.CHAT, List.of(MODEL))));

            assertEquals(LlmProviderService.PLATFORM_PROVIDER_NAME, response.name());
            assertTrue(response.platform());
            assertFalse(response.enabled(), "создаётся выключенным — включение после настройки квот");
        }

        @Test
        @DisplayName("повторное создание платформенного — 409")
        void rejectsDuplicate() {
            when(llmProviderRepository.findByUserIdAndName(
                    SystemSkillBootstrap.SYSTEM_USER_ID, LlmProviderService.PLATFORM_PROVIDER_NAME))
                    .thenReturn(Optional.of(existingProvider(true)));

            assertThrows(ru.agimate.common.rest.error.ConflictStatusException.class,
                    () -> service.createPlatformProvider(
                            new ru.agimate.controlapi.controller.manage.dto.llm.CreatePlatformLlmProviderRequest(
                                    LlmProviderType.OPENAI_COMPATIBLE, BASE_URL, API_KEY,
                            Map.of(LlmPurpose.CHAT, List.of(MODEL)))));
            verify(llmProviderRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("findUsablePlatformProvider")
    class FindUsable {

        @Test
        @DisplayName("включён → присутствует")
        void usable() {
            when(llmProviderRepository.findByUserIdAndName(
                    SystemSkillBootstrap.SYSTEM_USER_ID, LlmProviderService.PLATFORM_PROVIDER_NAME))
                    .thenReturn(Optional.of(existingProvider(true)));
            assertTrue(service.findUsablePlatformProvider().isPresent());
        }

        @Test
        @DisplayName("выключен → отсутствует")
        void disabledIsNotUsable() {
            when(llmProviderRepository.findByUserIdAndName(
                    SystemSkillBootstrap.SYSTEM_USER_ID, LlmProviderService.PLATFORM_PROVIDER_NAME))
                    .thenReturn(Optional.of(existingProvider(false)));
            assertTrue(service.findUsablePlatformProvider().isEmpty());
        }

        @Test
        @DisplayName("без purpose_priority → всё равно присутствует: пригодность под назначение решает резолвер")
        void emptyPurposePriorityIsStillReturned() {
            LlmProvider provider = existingProvider(true);
            provider.setPurposePriority(null);
            when(llmProviderRepository.findByUserIdAndName(
                    SystemSkillBootstrap.SYSTEM_USER_ID, LlmProviderService.PLATFORM_PROVIDER_NAME))
                    .thenReturn(Optional.of(provider));
            assertTrue(service.findUsablePlatformProvider().isPresent());
        }
    }

    @Nested
    @DisplayName("admin-доступ к платформенному провайдеру")
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
        @DisplayName("листинг админа дополняется платформенной строкой с platform=true")
        void adminListIncludesPlatform() {
            when(llmProviderRepository.findAllByUserIdOrderByCreatedAtDesc(adminId)).thenReturn(List.of());
            when(llmProviderRepository.findByUserIdAndName(
                    SystemSkillBootstrap.SYSTEM_USER_ID, LlmProviderService.PLATFORM_PROVIDER_NAME))
                    .thenReturn(Optional.of(existingProvider(true)));

            var list = service.listForUser(adminId, true);

            assertEquals(1, list.size());
            assertTrue(list.get(0).platform());

            assertTrue(service.listForUser(adminId, false).isEmpty(),
                    "без роли ADMIN платформенная строка не видна");
        }

        @Test
        @DisplayName("платформенную строку нельзя переименовать и удалить")
        void platformRenameAndDeleteRejected() {
            LlmProvider platform = existingProvider(true);
            when(llmProviderRepository.findById(platform.getId())).thenReturn(Optional.of(platform));

            assertThrows(ru.agimate.common.rest.error.BadRequestStatusException.class,
                    () -> service.update(platform.getId(), adminId, true,
                            new ru.agimate.controlapi.controller.manage.dto.llm.UpdateLlmProviderRequest(
                                    "renamed", null, null, null, null, null, null)));

            assertThrows(ru.agimate.common.rest.error.BadRequestStatusException.class,
                    () -> service.delete(platform.getId(), adminId, true));
        }

        @Test
        @DisplayName("админ включает платформенный провайдер и задаёт purpose_priority через update")
        void adminEnablesAndSetsModel() {
            LlmProvider platform = existingProvider(false);
            when(llmProviderRepository.findById(platform.getId())).thenReturn(Optional.of(platform));
            when(llmProviderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var response = service.update(platform.getId(), adminId, true,
                    new ru.agimate.controlapi.controller.manage.dto.llm.UpdateLlmProviderRequest(
                            null, null, null,
                            Map.of(LlmPurpose.CHAT, List.of("gemini-flash"),
                                    LlmPurpose.VISION, List.of("gemini-flash")),
                            null, null, true));

            assertTrue(response.enabled());
            assertEquals(List.of("gemini-flash"), response.purposePriority().get(LlmPurpose.CHAT));
            assertEquals(List.of("gemini-flash"), response.purposePriority().get(LlmPurpose.VISION));
            assertTrue(response.platform());
        }

        @Test
        @DisplayName("media_transport задаётся через update: у OpenRouter и Polza один providerType")
        void updatesMediaTransport() {
            LlmProvider platform = existingProvider(false);
            when(llmProviderRepository.findById(platform.getId())).thenReturn(Optional.of(platform));
            when(llmProviderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var response = service.update(platform.getId(), adminId, true,
                    new ru.agimate.controlapi.controller.manage.dto.llm.UpdateLlmProviderRequest(
                            null, null, null, null, null,
                            ru.agimate.controlapi.database.enums.MediaTransportType.MEDIA_ENDPOINT, null));

            assertEquals(ru.agimate.controlapi.database.enums.MediaTransportType.MEDIA_ENDPOINT,
                    response.mediaTransport());
        }

        @Test
        @DisplayName("модель не из реестра → 400: список — это allowlist, опечатку никто не исправит на вызове")
        void rejectsModelOutsideRegistry() {
            LlmProvider platform = existingProvider(true);
            when(llmProviderRepository.findById(platform.getId())).thenReturn(Optional.of(platform));
            when(llmProviderModelRepository.findAllByLlmProviderIdOrderByModel(platform.getId()))
                    .thenReturn(List.of(LlmProviderModel.builder().model("gemini-flash").build()));

            assertThrows(ru.agimate.common.rest.error.BadRequestStatusException.class,
                    () -> service.update(platform.getId(), adminId, true,
                            new ru.agimate.controlapi.controller.manage.dto.llm.UpdateLlmProviderRequest(
                                    null, null, null,
                                    Map.of(LlmPurpose.CHAT, List.of("gemini-flahs")), null, null, null)));
            verify(llmProviderRepository, never()).save(any());
        }

        @Test
        @DisplayName("пустой список — это выключенное назначение, а не мусор: сохраняется как есть")
        void keepsEmptyListAsExplicitOff() {
            LlmProvider platform = existingProvider(true);
            when(llmProviderRepository.findById(platform.getId())).thenReturn(Optional.of(platform));
            when(llmProviderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var response = service.update(platform.getId(), adminId, true,
                    new ru.agimate.controlapi.controller.manage.dto.llm.UpdateLlmProviderRequest(
                            null, null, null, Map.of(LlmPurpose.IMAGE, List.of()), null, null, null));

            assertEquals(List.of(), response.purposePriority().get(LlmPurpose.IMAGE));
        }

        @Test
        @DisplayName("дубль и пустая строка в списке — 400")
        void rejectsMalformedList() {
            LlmProvider platform = existingProvider(true);
            when(llmProviderRepository.findById(platform.getId())).thenReturn(Optional.of(platform));

            assertThrows(ru.agimate.common.rest.error.BadRequestStatusException.class,
                    () -> service.update(platform.getId(), adminId, true,
                            new ru.agimate.controlapi.controller.manage.dto.llm.UpdateLlmProviderRequest(
                                    null, null, null,
                                    Map.of(LlmPurpose.CHAT, List.of("a", "a")), null, null, null)));
            assertThrows(ru.agimate.common.rest.error.BadRequestStatusException.class,
                    () -> service.update(platform.getId(), adminId, true,
                            new ru.agimate.controlapi.controller.manage.dto.llm.UpdateLlmProviderRequest(
                                    null, null, null,
                                    Map.of(LlmPurpose.CHAT, List.of(" ")), null, null, null)));
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

            assertThrows(ru.agimate.common.rest.error.BadRequestStatusException.class,
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
                .name(LlmProviderService.PLATFORM_PROVIDER_NAME)
                .providerType(LlmProviderType.OPENAI_COMPATIBLE)
                .baseUrl(BASE_URL)
                .purposePriority(Map.of(LlmPurpose.CHAT, List.of(MODEL)))
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
