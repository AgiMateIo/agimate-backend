package ru.agimate.controlapi.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.agimate.controlapi.database.entities.LlmProvider;
import ru.agimate.controlapi.database.entities.Secret;
import ru.agimate.controlapi.database.enums.LlmProviderType;
import ru.agimate.controlapi.database.repositories.LlmProviderRepository;
import ru.agimate.controlapi.database.repositories.SecretRepository;
import ru.agimate.controlapi.service.llm.LlmModelDiscoveryService;
import ru.agimate.controlapi.service.secret.SecretService;

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
                            LlmProviderType.OPENAI_COMPATIBLE, BASE_URL, API_KEY, MODEL));

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
                                    LlmProviderType.OPENAI_COMPATIBLE, BASE_URL, API_KEY, MODEL)));
            verify(llmProviderRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("findUsablePlatformProvider")
    class FindUsable {

        @Test
        @DisplayName("включён и с default_model → присутствует")
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
        @DisplayName("без default_model → отсутствует")
        void missingDefaultModelIsNotUsable() {
            LlmProvider provider = existingProvider(true);
            provider.setDefaultModel(null);
            when(llmProviderRepository.findByUserIdAndName(
                    SystemSkillBootstrap.SYSTEM_USER_ID, LlmProviderService.PLATFORM_PROVIDER_NAME))
                    .thenReturn(Optional.of(provider));
            assertTrue(service.findUsablePlatformProvider().isEmpty());
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
                                    "renamed", null, null, null, null)));

            assertThrows(ru.agimate.common.rest.error.BadRequestStatusException.class,
                    () -> service.delete(platform.getId(), adminId, true));
        }

        @Test
        @DisplayName("админ включает платформенный провайдер и меняет default_model через update")
        void adminEnablesAndSetsModel() {
            LlmProvider platform = existingProvider(false);
            when(llmProviderRepository.findById(platform.getId())).thenReturn(Optional.of(platform));
            when(llmProviderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var response = service.update(platform.getId(), adminId, true,
                    new ru.agimate.controlapi.controller.manage.dto.llm.UpdateLlmProviderRequest(
                            null, null, null, "gemini-flash", true));

            assertTrue(response.enabled());
            assertEquals("gemini-flash", response.defaultModel());
            assertTrue(response.platform());
        }
    }

    private static LlmProvider existingProvider(boolean enabled) {
        return LlmProvider.builder()
                .id(UUID.randomUUID())
                .userId(SystemSkillBootstrap.SYSTEM_USER_ID)
                .name(LlmProviderService.PLATFORM_PROVIDER_NAME)
                .providerType(LlmProviderType.OPENAI_COMPATIBLE)
                .baseUrl(BASE_URL)
                .defaultModel(MODEL)
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
