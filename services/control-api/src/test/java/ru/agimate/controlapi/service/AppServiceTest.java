package ru.agimate.controlapi.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.controlapi.controller.app.dto.AppDeclaration;
import ru.agimate.controlapi.controller.app.dto.LinkDeviceRequest;
import ru.agimate.controlapi.controller.app.dto.SkillDeclaration;
import ru.agimate.controlapi.database.entities.App;
import ru.agimate.controlapi.database.entities.Connection;
import ru.agimate.controlapi.database.entities.Connector;
import ru.agimate.controlapi.database.entities.Skill;
import ru.agimate.controlapi.database.enums.ContentLanguage;
import ru.agimate.controlapi.database.enums.ExecutionKind;
import ru.agimate.controlapi.database.model.AppSkill;
import ru.agimate.controlapi.database.repositories.AppRepository;
import ru.agimate.controlapi.database.repositories.ConnectionRepository;
import ru.agimate.controlapi.database.repositories.ConnectionToolRepository;
import ru.agimate.controlapi.database.repositories.ConnectionTriggerRepository;
import ru.agimate.controlapi.database.repositories.ConnectorRepository;
import ru.agimate.controlapi.security.AppPrincipal;
import ru.agimate.controlapi.service.connection.ConnectionBindingService;
import ru.agimate.controlapi.service.dto.AppCreateResult;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AppService — линковка, идентичность и навык приложения")
class AppServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID APP_ID = UUID.randomUUID();
    private static final AppPrincipal PRINCIPAL = new AppPrincipal("app", APP_ID, USER_ID);

    @Mock
    private AppRepository appRepository;
    @Mock
    private ConnectionRepository connectionRepository;
    @Mock
    private ConnectionToolRepository connectionToolRepository;
    @Mock
    private ConnectionTriggerRepository connectionTriggerRepository;
    @Mock
    private ConnectorRepository connectorRepository;
    @Mock
    private ConnectionBindingService connectionBindingService;
    @Mock
    private SkillService skillService;

    @InjectMocks
    private AppService service;

    @BeforeEach
    void defaults() {
        when(appRepository.save(any(App.class))).thenAnswer(inv -> inv.getArgument(0));
        when(connectionRepository.save(any(Connection.class))).thenAnswer(inv -> inv.getArgument(0));
        when(connectionRepository.existsByFullCodeAndUserIdAndDeletedAtIsNull(anyString(), any())).thenReturn(false);
    }

    private static App app(String deviceId, String code, String title, String version) {
        return App.builder().id(APP_ID).userId(USER_ID).connectorCode("app")
                .deviceId(deviceId).code(code).title(title).version(version).build();
    }

    private static Connection connection(String fullCode, String subCode) {
        return Connection.builder().id(APP_ID).appId(APP_ID).userId(USER_ID).connectorCode("app")
                .fullCode(fullCode).subCode(subCode).build();
    }

    private LinkDeviceRequest request(String deviceId, AppDeclaration app, SkillDeclaration skill) {
        return new LinkDeviceRequest(deviceId, "MacBook", "macOS", Map.of(), Map.of(), Map.of(), app, skill);
    }

    private static SkillDeclaration skillDeclaration() {
        return new SkillDeclaration(null, "Takes screenshots", "Body.", List.of("Take a screenshot"),
                ContentLanguage.RU);
    }

    private static AppDeclaration appDeclaration(String code) {
        return new AppDeclaration(code, "AgiMate Desktop", "1.4.2");
    }

    @Nested
    @DisplayName("создание ключа")
    class Creation {

        @BeforeEach
        void knownConnector() {
            when(connectorRepository.findById("app")).thenReturn(Optional.of(
                    Connector.builder().code("app").executionKind(ExecutionKind.APP).build()));
        }

        @Test
        @DisplayName("ключ создаётся без имени, с placeholder app_<appId> в namespace")
        void placeholderNamespace() {
            when(appRepository.save(any(App.class))).thenAnswer(inv -> {
                App saved = inv.getArgument(0);
                saved.setId(APP_ID);
                return saved;
            });
            when(appRepository.countByUserIdNotDeleted(USER_ID)).thenReturn(0L);

            AppCreateResult result = service.createApp(USER_ID, "note", "app");

            assertNull(result.app().getCode());
            ArgumentCaptor<Connection> captor = ArgumentCaptor.forClass(Connection.class);
            verify(connectionRepository).save(captor.capture());
            assertEquals("app_" + APP_ID, captor.getValue().getFullCode());
            assertNull(captor.getValue().getSubCode());
            assertNull(captor.getValue().getName());
        }

        @Test
        @DisplayName("два неслинкованных приложения не сталкиваются на full_code")
        void twoUnlinkedAppsDoNotCollide() {
            UUID first = UUID.randomUUID();
            UUID second = UUID.randomUUID();
            AtomicInteger index = new AtomicInteger();
            when(appRepository.save(any(App.class))).thenAnswer(inv -> {
                App saved = inv.getArgument(0);
                saved.setId(index.getAndIncrement() == 0 ? first : second);
                return saved;
            });
            when(appRepository.countByUserIdNotDeleted(USER_ID)).thenReturn(0L);

            service.createApp(USER_ID, null, "app");
            service.createApp(USER_ID, null, "app");

            ArgumentCaptor<Connection> captor = ArgumentCaptor.forClass(Connection.class);
            verify(connectionRepository, times(2)).save(captor.capture());
            assertEquals("app_" + first, captor.getAllValues().get(0).getFullCode());
            assertEquals("app_" + second, captor.getAllValues().get(1).getFullCode());
            assertFalse(captor.getAllValues().get(0).getFullCode()
                    .equals(captor.getAllValues().get(1).getFullCode()));
        }
    }

    @Nested
    @DisplayName("линковка")
    class Linking {

        @Test
        @DisplayName("первый линк без блока app — работает как раньше: ни кода, ни навыка")
        void oldClientStillLinks() {
            App app = app(null, null, null, null);
            Connection connection = connection("app_" + APP_ID, null);
            when(appRepository.findByIdForUpdate(APP_ID)).thenReturn(Optional.of(app));
            when(connectionRepository.findByAppIdAndDeletedAtIsNull(APP_ID)).thenReturn(Optional.of(connection));

            App result = service.linkDevice(PRINCIPAL, request("dev-1", null, null));

            assertSame(app, result);
            assertTrue(app.isLinked());
            assertNull(app.getCode());
            assertNull(app.getSkill());
            verifyNoInteractions(skillService);
            assertEquals("app_" + APP_ID, connection.getFullCode());
            verify(connectionRepository, never()).existsByFullCodeAndUserIdAndDeletedAtIsNull(anyString(), any());
        }

        @Test
        @DisplayName("блок app ставит code/title/version, namespace app_<slug> и имя подключения")
        void appBlockAssignsIdentityAndNamespace() {
            App app = app(null, null, null, null);
            Connection connection = connection("app_" + APP_ID, null);
            when(appRepository.findByIdForUpdate(APP_ID)).thenReturn(Optional.of(app));
            when(connectionRepository.findByAppIdAndDeletedAtIsNull(APP_ID)).thenReturn(Optional.of(connection));
            when(skillService.createFromApp(any(), any(), anyString(), anyString(), any()))
                    .thenReturn(Optional.of(Skill.builder().id(UUID.randomUUID()).build()));

            service.linkDevice(PRINCIPAL, request("dev-1", appDeclaration("desktop"), skillDeclaration()));

            assertEquals("desktop", app.getCode());
            assertEquals("AgiMate Desktop", app.getTitle());
            assertEquals("1.4.2", app.getVersion());
            assertNotNull(app.getSkill());
            assertEquals("app_desktop", connection.getFullCode());
            assertEquals("desktop", connection.getSubCode());
            assertEquals("AgiMate Desktop", connection.getName());
            verify(skillService).createFromApp(eq(USER_ID), eq(APP_ID), eq("desktop"),
                    eq("AgiMate Desktop"), any(AppSkill.class));
        }

        @Test
        @DisplayName("коллизия namespace получает суффикс _2")
        void namespaceCollisionGetsSuffix() {
            when(appRepository.findByIdForUpdate(APP_ID)).thenReturn(Optional.of(app(null, null, null, null)));
            Connection connection = connection("app_" + APP_ID, null);
            when(connectionRepository.findByAppIdAndDeletedAtIsNull(APP_ID)).thenReturn(Optional.of(connection));
            when(connectionRepository.existsByFullCodeAndUserIdAndDeletedAtIsNull("app_desktop", USER_ID))
                    .thenReturn(true);
            when(skillService.createFromApp(any(), any(), anyString(), anyString(), any()))
                    .thenReturn(Optional.of(Skill.builder().build()));

            service.linkDevice(PRINCIPAL, request("dev-1", appDeclaration("desktop"), null));

            assertEquals("app_desktop_2", connection.getFullCode());
            assertEquals("desktop", connection.getSubCode());
        }

        @Test
        @DisplayName("my-app → namespace app_my_app, имя навыка app-my-app")
        void hyphenInCodeBecomesUnderscoreInNamespace() {
            when(appRepository.findByIdForUpdate(APP_ID)).thenReturn(Optional.of(app(null, null, null, null)));
            Connection connection = connection("app_" + APP_ID, null);
            when(connectionRepository.findByAppIdAndDeletedAtIsNull(APP_ID)).thenReturn(Optional.of(connection));
            when(skillService.createFromApp(any(), any(), anyString(), anyString(), any()))
                    .thenReturn(Optional.of(Skill.builder().build()));

            service.linkDevice(PRINCIPAL, request("dev-1", appDeclaration("my-app"), skillDeclaration()));

            assertEquals("app_my_app", connection.getFullCode());
            assertEquals("my_app", connection.getSubCode());
            verify(skillService).createFromApp(eq(USER_ID), eq(APP_ID), eq("my-app"), anyString(), any());
        }

        @Test
        @DisplayName("переходная строка: старая метка заменяется кодом, namespace не меняется")
        void transitionalRowKeepsNamespace() {
            App app = app("dev-0", "My Home Device", null, null);
            Connection connection = connection("app_my_home_device", "my_home_device");
            when(appRepository.findByIdForUpdate(APP_ID)).thenReturn(Optional.of(app));
            when(connectionRepository.findByAppIdAndDeletedAtIsNull(APP_ID)).thenReturn(Optional.of(connection));

            service.linkDevice(PRINCIPAL, request("dev-0", appDeclaration("desktop"), null));

            assertEquals("desktop", app.getCode());
            assertEquals("AgiMate Desktop", app.getTitle());
            assertEquals("1.4.2", app.getVersion());
            assertEquals("app_my_home_device", connection.getFullCode());
            assertEquals("my_home_device", connection.getSubCode());
        }

        @Test
        @DisplayName("смена кода после первого блока игнорируется, версия обновляется")
        void laterCodeChangeIgnored() {
            App app = app("dev-0", "desktop", "AgiMate Desktop", "1.0");
            Connection connection = connection("app_desktop", "desktop");
            when(appRepository.findByIdForUpdate(APP_ID)).thenReturn(Optional.of(app));
            when(connectionRepository.findByAppIdAndDeletedAtIsNull(APP_ID)).thenReturn(Optional.of(connection));

            service.linkDevice(PRINCIPAL, request("dev-0", appDeclaration("chromium"), null));

            assertEquals("desktop", app.getCode());
            assertEquals("1.4.2", app.getVersion());
            assertEquals("app_desktop", connection.getFullCode());
        }

        @Test
        @DisplayName("skill без app → 400: имя навыка не из чего собрать")
        void skillWithoutAppIsRejected() {
            when(appRepository.findByIdForUpdate(APP_ID)).thenReturn(Optional.of(app(null, null, null, null)));

            assertThrows(BadRequestStatusException.class,
                    () -> service.linkDevice(PRINCIPAL, request("dev-1", null, skillDeclaration())));
            verify(appRepository, never()).save(any());
        }

        @Test
        @DisplayName("другой deviceId → null (409), состояние не меняется")
        void anotherDeviceConflicts() {
            App app = app("dev-0", "desktop", "AgiMate Desktop", "1.0");
            when(appRepository.findByIdForUpdate(APP_ID)).thenReturn(Optional.of(app));

            App result = service.linkDevice(PRINCIPAL, request("dev-1", appDeclaration("chromium"), null));

            assertNull(result);
            assertEquals("dev-0", app.getDeviceId());
            assertEquals("1.0", app.getVersion());
            verify(appRepository, never()).save(any());
        }

        @Test
        @DisplayName("без блока skill apps.skill не меняется и навык не трогается")
        void missingSkillBlockKeepsDeclaration() {
            AppSkill stored = new AppSkill("T", "d", "b", List.of(), ContentLanguage.EN);
            App app = app("dev-0", "desktop", "AgiMate Desktop", "1.0");
            app.setSkill(stored);
            when(appRepository.findByIdForUpdate(APP_ID)).thenReturn(Optional.of(app));
            when(connectionRepository.findByAppIdAndDeletedAtIsNull(APP_ID))
                    .thenReturn(Optional.of(connection("app_desktop", "desktop")));

            service.linkDevice(PRINCIPAL, request("dev-0", appDeclaration("desktop"), null));

            assertSame(stored, app.getSkill());
            verifyNoInteractions(skillService);
        }

        @Test
        @DisplayName("существующий навык обновляется, только если он ещё не правился")
        void existingSkillIsRefreshed() {
            App app = app("dev-0", "desktop", "AgiMate Desktop", "1.0");
            Skill live = Skill.builder().id(UUID.randomUUID()).name("app-desktop").build();
            when(appRepository.findByIdForUpdate(APP_ID)).thenReturn(Optional.of(app));
            when(connectionRepository.findByAppIdAndDeletedAtIsNull(APP_ID))
                    .thenReturn(Optional.of(connection("app_desktop", "desktop")));
            when(skillService.createFromApp(any(), any(), anyString(), anyString(), any()))
                    .thenReturn(Optional.empty());
            when(skillService.findLiveOriginSkill(APP_ID)).thenReturn(Optional.of(live));

            service.linkDevice(PRINCIPAL, request("dev-0", appDeclaration("desktop"), skillDeclaration()));

            verify(skillService).applyAppDeclaration(eq(live), eq("desktop"), eq("AgiMate Desktop"),
                    any(AppSkill.class));
        }

        @Test
        @DisplayName("код нормализуется: trim и нижний регистр")
        void codeIsNormalized() {
            App app = app(null, null, null, null);
            when(appRepository.findByIdForUpdate(APP_ID)).thenReturn(Optional.of(app));
            when(connectionRepository.findByAppIdAndDeletedAtIsNull(APP_ID))
                    .thenReturn(Optional.of(connection("app_" + APP_ID, null)));

            service.linkDevice(PRINCIPAL, request("dev-1",
                    new AppDeclaration("  Desktop ", "AgiMate Desktop", "1.4.2"), null));

            assertEquals("desktop", app.getCode());
        }

        @Test
        @DisplayName("код длиной 48 принимается — namespace не укорачивается")
        void codeOf48IsAccepted() {
            App app = app(null, null, null, null);
            when(appRepository.findByIdForUpdate(APP_ID)).thenReturn(Optional.of(app));
            when(connectionRepository.findByAppIdAndDeletedAtIsNull(APP_ID))
                    .thenReturn(Optional.of(connection("app_" + APP_ID, null)));
            String code = "a".repeat(48);

            service.linkDevice(PRINCIPAL, request("dev-1", new AppDeclaration(code, "T", "1"), null));

            assertEquals(code, app.getCode());
        }

        @Test
        @DisplayName("код не kebab-case → 400")
        void invalidCodeIsRejected() {
            when(appRepository.findByIdForUpdate(APP_ID)).thenReturn(Optional.of(app(null, null, null, null)));

            assertThrows(BadRequestStatusException.class, () -> service.linkDevice(PRINCIPAL,
                    request("dev-1", new AppDeclaration("a--b", "T", "1"), null)));
            assertThrows(BadRequestStatusException.class, () -> service.linkDevice(PRINCIPAL,
                    request("dev-1", new AppDeclaration("desktop-", "T", "1"), null)));
            assertThrows(BadRequestStatusException.class, () -> service.linkDevice(PRINCIPAL,
                    request("dev-1", new AppDeclaration("a".repeat(49), "T", "1"), null)));
        }

        @Test
        @DisplayName("пустое описание навыка или тело → 400")
        void emptySkillTextIsRejected() {
            when(appRepository.findByIdForUpdate(APP_ID)).thenReturn(Optional.of(app(null, null, null, null)));

            assertThrows(BadRequestStatusException.class, () -> service.linkDevice(PRINCIPAL, request("dev-1",
                    appDeclaration("desktop"), new SkillDeclaration(null, "  ", "body", List.of(), null))));
            assertThrows(BadRequestStatusException.class, () -> service.linkDevice(PRINCIPAL, request("dev-1",
                    appDeclaration("desktop"), new SkillDeclaration(null, "d", " ", List.of(), null))));
        }
    }

    @Nested
    @DisplayName("обновление и удаление")
    class UpdateAndDelete {

        @Test
        @DisplayName("enabled синхронизируется с подключением")
        void enabledFollowsConnection() {
            App app = app(null, "desktop", "AgiMate Desktop", "1.0");
            Connection connection = connection("app_desktop", "desktop");
            when(appRepository.findByIdNotDeleted(APP_ID)).thenReturn(Optional.of(app));
            when(connectionRepository.findByAppIdAndDeletedAtIsNull(APP_ID)).thenReturn(Optional.of(connection));

            service.updateApp(APP_ID, USER_ID, null, false);

            assertFalse(app.getEnabled());
            assertFalse(connection.getEnabled());
            verify(connectionRepository).save(connection);
        }

        @Test
        @DisplayName("удаление приложения удаляет origin-навык")
        void deleteRemovesOriginSkill() {
            App app = app(null, "desktop", "AgiMate Desktop", "1.0");
            when(appRepository.findByIdNotDeleted(APP_ID)).thenReturn(Optional.of(app));
            when(connectionRepository.findByAppIdAndDeletedAtIsNull(APP_ID)).thenReturn(Optional.empty());

            service.deleteApp(APP_ID, USER_ID);

            verify(appRepository).softDelete(eq(APP_ID), any());
            verify(skillService).deleteOriginSkill(APP_ID, USER_ID);
        }
    }
}
