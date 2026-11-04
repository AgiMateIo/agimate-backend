package ru.agimate.controlapi.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.LinkedHashMap;
import ru.agimate.controlapi.database.enums.ContentLanguage;
import ru.agimate.controlapi.service.seed.ContentLanguageResolver;
import ru.agimate.controlapi.connectors.core.dto.CredentialField;
import ru.agimate.controlapi.connectors.core.IntegrationConnectorHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.ConflictStatusException;
import ru.agimate.common.rest.error.ForbiddenStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.controlapi.controller.manage.dto.CreateSkillRequest;
import ru.agimate.controlapi.controller.manage.dto.SkillDetailResponse;
import ru.agimate.controlapi.controller.manage.dto.SkillResponse;
import ru.agimate.controlapi.controller.manage.dto.UpdateSkillConnectorsRequest;
import ru.agimate.controlapi.controller.manage.dto.UpdateSkillRequest;
import ru.agimate.controlapi.database.entities.App;
import ru.agimate.controlapi.database.entities.Connection;
import ru.agimate.controlapi.database.entities.Skill;
import ru.agimate.controlapi.controller.manage.dto.SkillListScope;
import ru.agimate.controlapi.database.enums.ContentCategory;
import ru.agimate.controlapi.database.enums.Disclosure;
import ru.agimate.controlapi.database.enums.SkillUpdatedBy;
import ru.agimate.controlapi.database.model.AppSkill;
import ru.agimate.controlapi.database.repositories.AgentPresetRepository;
import ru.agimate.controlapi.database.repositories.AgentRepository;
import ru.agimate.controlapi.database.repositories.AgentSkillRepository;
import ru.agimate.controlapi.database.repositories.AppRepository;
import ru.agimate.controlapi.database.repositories.ConnectionRepository;
import ru.agimate.controlapi.database.repositories.ConnectorRepository;
import ru.agimate.controlapi.database.repositories.SkillRepository;

import ru.agimate.controlapi.database.model.ConnectorRequirement;
import ru.agimate.controlapi.connectors.core.ConnectorRegistry;
import ru.agimate.controlapi.abac.SkillPolicySync;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static ru.agimate.controlapi.service.SystemSkillBootstrap.SYSTEM_USER_ID;

@ExtendWith(MockitoExtension.class)
@DisplayName("SkillService")
class SkillServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID SKILL_ID = UUID.randomUUID();

    @Mock
    private SkillRepository skillRepository;
    @Mock
    private AppRepository appRepository;
    @Mock
    private AgentRepository agentRepository;
    @Mock
    private AgentSkillRepository agentSkillRepository;
    @Mock
    private AgentPresetRepository agentPresetRepository;
    @Mock
    private ConnectorRepository connectorRepository;
    @Mock
    private ConnectionRepository connectionRepository;
    @Mock
    private ConnectorRegistry connectorRegistry;
    @Mock
    private SkillPolicySync skillPolicySync;
    @Mock
    private ContentLanguageResolver contentLanguageResolver;

    @InjectMocks
    private SkillService service;

    @Nested
    @DisplayName("create — валидация connector_codes")
    class Create {

        private void knownConnectors(String... codes) {
            List<String> known = List.of(codes);
            when(connectorRepository.existsById(any(String.class)))
                    .thenAnswer(inv -> known.contains(inv.<String>getArgument(0)));
        }

        private String skillMd(String connectorsYaml) {
            return """
                    ---
                    name: test-skill
                    description: d
                    connectors:
                    %s
                    ---
                    Body.
                    """.formatted(connectorsYaml);
        }

        @Test
        @DisplayName("неизвестный код коннектора → 400 с перечислением")
        void rejectsUnknownConnectorCode() {
            knownConnectors("board");
            CreateSkillRequest request = new CreateSkillRequest(skillMd("  - board\n  - bogus"), false);

            BadRequestStatusException ex = assertThrows(BadRequestStatusException.class,
                    () -> service.create(USER_ID, request));
            assertTrue(ex.getMessage().contains("bogus"), ex.getMessage());
        }

        @Test
        @DisplayName("все коды известны → скилл создаётся")
        void acceptsKnownConnectorCodes() {
            knownConnectors("board");
            when(skillRepository.existsByKeyNotDeleted(USER_ID, "test-skill", null)).thenReturn(false);
            when(skillRepository.save(any(Skill.class))).thenAnswer(inv -> {
                Skill s = inv.getArgument(0);
                s.setId(UUID.randomUUID());
                return s;
            });
            CreateSkillRequest request = new CreateSkillRequest(skillMd("  - board"), false);

            SkillResponse response = service.create(USER_ID, request);

            assertEquals("test-skill", response.name());
            assertEquals(List.of("board"), response.connectorCodes());
            verify(skillRepository).save(argThat(skill -> skill.getLanguage() == null));
        }
    }

    @Nested
    @DisplayName("delete — soft-delete скилла + чистка agent_skills")
    class Delete {

        @Test
        @DisplayName("удаляет привязки ко всем агентам вместе со скиллом")
        void cleansAgentSkills() {
            when(skillRepository.findByIdNotDeleted(SKILL_ID)).thenReturn(Optional.of(
                    Skill.builder().id(SKILL_ID).userId(USER_ID).name("test-skill").build()));
            when(agentSkillRepository.deleteBySkillId(SKILL_ID)).thenReturn(2);

            service.delete(SKILL_ID, USER_ID, false);

            verify(skillRepository).softDelete(eq(SKILL_ID), any());
            verify(agentSkillRepository).deleteBySkillId(SKILL_ID);
        }

        @Test
        @DisplayName("чужой скилл → Forbidden, привязки не тронуты")
        void foreignSkillForbidden() {
            when(skillRepository.findByIdNotDeleted(SKILL_ID)).thenReturn(Optional.of(
                    Skill.builder().id(SKILL_ID).userId(UUID.randomUUID()).name("test-skill").build()));

            assertThrows(ForbiddenStatusException.class, () -> service.delete(SKILL_ID, USER_ID, false));

            verifyNoInteractions(agentSkillRepository);
            verify(skillRepository, never()).softDelete(any(), any());
        }

        @Test
        @DisplayName("скилл не найден/уже удалён → NotFound")
        void missingSkillNotFound() {
            when(skillRepository.findByIdNotDeleted(SKILL_ID)).thenReturn(Optional.empty());

            assertThrows(NotFoundStatusException.class, () -> service.delete(SKILL_ID, USER_ID, false));

            verifyNoInteractions(agentSkillRepository);
        }
    }

    @Nested
    @DisplayName("системные скилы — доступ и guardrails ADMIN")
    class SystemSkills {

        private Skill systemSkill() {
            return Skill.builder().id(SKILL_ID).userId(SYSTEM_USER_ID).name("board").language(ContentLanguage.RU)
                    .isPublic(true).version(1).connectors(ConnectorRequirement.ofCodes(List.of("board")))
                    .mdContent("body").description("d").build();
        }

        @Test
        @DisplayName("не-админ не может править системный скилл → Forbidden")
        void nonAdminCannotEditSystem() {
            when(skillRepository.findByIdNotDeleted(SKILL_ID)).thenReturn(Optional.of(systemSkill()));

            assertThrows(ForbiddenStatusException.class,
                    () -> service.update(SKILL_ID, USER_ID, false,
                            new UpdateSkillRequest(systemSkillMd("board"), true)));
        }

        @Test
        @DisplayName("админ правит тело системного скилла → version++")
        void adminEditsSystemBumpsVersion() {
            when(skillRepository.findByIdNotDeleted(SKILL_ID)).thenReturn(Optional.of(systemSkill()));
            when(skillRepository.save(any(Skill.class))).thenAnswer(inv -> inv.getArgument(0));

            SkillResponse response = service.update(SKILL_ID, USER_ID, true,
                    new UpdateSkillRequest(systemSkillMd("board"), true));

            assertEquals(2, response.version());
            assertTrue(response.system());
        }

        @Test
        @DisplayName("update без isPublic не снимает публичность")
        void omittedIsPublicKeepsVisibility() {
            when(skillRepository.findByIdNotDeleted(SKILL_ID)).thenReturn(Optional.of(systemSkill()));
            when(skillRepository.save(any(Skill.class))).thenAnswer(inv -> inv.getArgument(0));

            SkillResponse response = service.update(SKILL_ID, USER_ID, true,
                    new UpdateSkillRequest(systemSkillMd("board"), null));

            assertTrue(response.isPublic());
        }

        @Test
        @DisplayName("переименование системного скилла запрещено → 400")
        void renameSystemForbidden() {
            when(skillRepository.findByIdNotDeleted(SKILL_ID)).thenReturn(Optional.of(systemSkill()));

            assertThrows(BadRequestStatusException.class,
                    () -> service.update(SKILL_ID, USER_ID, true,
                            new UpdateSkillRequest(systemSkillMd("renamed"), true)));
        }

        @Test
        @DisplayName("delete системного скилла с привязками → 409, не удаляется")
        void deleteBoundSystemConflict() {
            when(skillRepository.findByIdNotDeleted(SKILL_ID)).thenReturn(Optional.of(systemSkill()));
            when(agentSkillRepository.existsBySkillId(SKILL_ID)).thenReturn(true);

            assertThrows(ConflictStatusException.class, () -> service.delete(SKILL_ID, USER_ID, true));

            verify(skillRepository, never()).softDelete(any(), any());
        }

        @Test
        @DisplayName("delete системного скилла с ссылкой из пресета → 409")
        void deletePresetReferencedSystemConflict() {
            when(skillRepository.findByIdNotDeleted(SKILL_ID)).thenReturn(Optional.of(systemSkill()));
            when(agentSkillRepository.existsBySkillId(SKILL_ID)).thenReturn(false);
            when(agentPresetRepository.existsBySkillNameReferenced("board", ContentLanguage.RU)).thenReturn(true);

            assertThrows(ConflictStatusException.class, () -> service.delete(SKILL_ID, USER_ID, true));

            verify(skillRepository, never()).softDelete(any(), any());
        }

        @Test
        @DisplayName("createSystem — owner=SYSTEM, всегда public, каталог языка запроса")
        void createSystemForcesOwnerAndPublic() {
            lenient().when(contentLanguageResolver.defaultLanguage()).thenReturn(ContentLanguage.RU);
            when(contentLanguageResolver.requestLanguage()).thenReturn(ContentLanguage.EN);
            when(skillRepository.existsByKeyNotDeleted(SYSTEM_USER_ID, "new", ContentLanguage.EN)).thenReturn(false);
            when(skillRepository.save(any(Skill.class))).thenAnswer(inv -> {
                Skill s = inv.getArgument(0);
                s.setId(UUID.randomUUID());
                return s;
            });

            SkillResponse response = service.createSystem(new CreateSkillRequest(systemSkillMd("new"), false));

            assertTrue(response.isPublic());
            assertTrue(response.system());
            assertEquals(SYSTEM_USER_ID, response.userId());
            verify(skillRepository).save(argThat(skill -> skill.getLanguage() == ContentLanguage.EN));
        }

        private String systemSkillMd(String name) {
            return """
                    ---
                    name: %s
                    description: d
                    ---
                    Body.
                    """.formatted(name);
        }
    }

    @Nested
    @DisplayName("getSkills — навыки для подключения")
    class ForConnection {

        private final UUID userId = UUID.randomUUID();
        private final UUID connectionId = UUID.randomUUID();

        @Test
        @DisplayName("connectionId вместе с connectorCode → 400: код уже задан подключением")
        void exclusiveWithConnectorCode() {
            assertThrows(BadRequestStatusException.class, () -> service.getSkills(userId, SkillListScope.ALL,
                    null, "mcp", connectionId, null, null, 0, 20));
        }

        @Test
        @DisplayName("чужое подключение → 404")
        void foreignConnection() {
            when(connectionRepository.findByIdNotDeleted(connectionId)).thenReturn(Optional.of(
                    Connection.builder().id(connectionId).userId(UUID.randomUUID()).build()));

            assertThrows(NotFoundStatusException.class, () -> service.getSkills(userId, SkillListScope.ALL,
                    null, null, connectionId, null, null, 0, 20));
        }
    }

    @Nested
    @DisplayName("validateConnectors — params требования против формы коннектора")
    class Params {

        private ConnectorRequirement mcp(Map<String, String> params) {
            return new ConnectorRequirement("mcp", "mcp", null, params, null, null);
        }

        @BeforeEach
        void known() {
            // Each test walks a different branch of the check, so not every stub is hit in every test.
            lenient().when(connectorRepository.existsById("mcp")).thenReturn(true);
            lenient().when(connectorRepository.existsById("board")).thenReturn(true);
            IntegrationConnectorHandler handler = mock(IntegrationConnectorHandler.class);
            Map<String, CredentialField> fields = new LinkedHashMap<>();
            fields.put("url", CredentialField.required("URL", CredentialField.Type.URL));
            fields.put("auth_token", CredentialField.optional("Token", CredentialField.Type.SECRET));
            lenient().when(handler.getCredentialFields()).thenReturn(fields);
            lenient().when(connectorRegistry.findIntegrationHandler("mcp")).thenReturn(Optional.of(handler));
            lenient().when(connectorRegistry.findIntegrationHandler("board")).thenReturn(Optional.empty());
        }

        @Test
        @DisplayName("несекретное объявленное поле — принимается")
        void nonSecretDeclaredField() {
            assertDoesNotThrow(() -> service.validateConnectors(List.of(mcp(Map.of("url", "https://x")))));
        }

        @Test
        @DisplayName("params нормализуются по типу поля, как у подключения: URL без пробелов")
        void paramsNormalised() {
            List<ConnectorRequirement> normalised = service.validateConnectors(List.of(mcp(Map.of("url", " https://x "))));
            assertEquals(Map.of("url", "https://x"), normalised.get(0).params());
        }

        @Test
        @DisplayName("SECRET-поле в params → 400: навык читают все, кому он предложен")
        void secretRejected() {
            BadRequestStatusException ex = assertThrows(BadRequestStatusException.class,
                    () -> service.validateConnectors(List.of(mcp(Map.of("auth_token", "t")))));
            assertTrue(ex.getMessage().contains("auth_token"), ex.getMessage());
        }

        @Test
        @DisplayName("URL с ключом в query → 400: подключение такой URL держит в секрете")
        void urlWithKeyRejected() {
            assertThrows(BadRequestStatusException.class,
                    () -> service.validateConnectors(List.of(mcp(Map.of("url", "https://x?api_key=k")))));
        }

        @Test
        @DisplayName("поле, которого нет в форме → 400")
        void unknownFieldRejected() {
            assertThrows(BadRequestStatusException.class,
                    () -> service.validateConnectors(List.of(mcp(Map.of("host", "x")))));
        }

        @Test
        @DisplayName("params у коннектора без формы (внутреннего) → 400")
        void paramsOnInternalRejected() {
            assertThrows(BadRequestStatusException.class, () -> service.validateConnectors(
                    List.of(new ConnectorRequirement("board", "board", null, Map.of("x", "y"), null, null))));
        }
    }

    @Nested
    @DisplayName("навык, который принесло приложение")
    class AppBroughtSkills {

        private final UUID originId = UUID.randomUUID();

        @BeforeEach
        void knownConnectors() {
            lenient().when(connectorRepository.existsById("app")).thenReturn(true);
            lenient().when(connectorRepository.existsById("board")).thenReturn(true);
        }

        private Skill originSkill(SkillUpdatedBy updatedBy) {
            return Skill.builder().id(SKILL_ID).userId(USER_ID).name("app-desktop").version(1)
                    .title("Old title").description("old description").mdContent("old body")
                    .connectors(ConnectorRequirement.ofCodes(List.of("app")))
                    .originConnectionId(originId).updatedBy(updatedBy).build();
        }

        private String md(String name, String connectors) {
            return """
                    ---
                    name: %s
                    description: d
                    %s
                    ---
                    Body.
                    """.formatted(name, connectors);
        }

        private String withoutConnectors(String name) {
            return """
                    ---
                    name: %s
                    description: d
                    ---
                    Body.
                    """.formatted(name);
        }

        private AppSkill declaration() {
            return new AppSkill("New title", "new description", "new body", List.of("do it"), ContentLanguage.RU);
        }

        @Test
        @DisplayName("createFromApp ставит принудительные поля, origin и updated_by=APP")
        void createForcesFields() {
            when(skillRepository.findByOriginConnectionId(originId)).thenReturn(Optional.empty());
            when(skillRepository.existsByKeyNotDeleted(USER_ID, "app-desktop", null)).thenReturn(false);
            when(skillRepository.save(any(Skill.class))).thenAnswer(inv -> {
                Skill s = inv.getArgument(0);
                s.setId(SKILL_ID);
                return s;
            });

            Skill skill = service.createFromApp(USER_ID, originId, "desktop", "AgiMate Desktop",
                    new AppSkill(null, "desc", "body", List.of("do it"), ContentLanguage.RU)).orElseThrow();

            assertEquals("app-desktop", skill.getName());
            assertEquals("AgiMate Desktop", skill.getTitle());
            assertEquals(List.of("app"), skill.getConnectorCodes());
            assertEquals(ContentCategory.PLATFORM, skill.getCategory());
            assertEquals(Disclosure.LAZY, skill.getDisclosure());
            assertEquals(SkillUpdatedBy.APP, skill.getUpdatedBy());
            assertEquals(originId, skill.getOriginConnectionId());
            assertNull(skill.getLanguage());
            assertFalse(skill.getIsPublic());
            assertEquals(List.of("do it"), skill.getStarters());
        }

        @Test
        @DisplayName("имя app-<code> при коллизии получает суффикс -2, затем -3")
        void nameCollisionGetsHyphenSuffix() {
            when(skillRepository.findByOriginConnectionId(originId)).thenReturn(Optional.empty());
            when(skillRepository.existsByKeyNotDeleted(USER_ID, "app-desktop", null)).thenReturn(true);
            when(skillRepository.existsByKeyNotDeleted(USER_ID, "app-desktop-2", null)).thenReturn(true);
            when(skillRepository.existsByKeyNotDeleted(USER_ID, "app-desktop-3", null)).thenReturn(false);
            when(skillRepository.save(any(Skill.class))).thenAnswer(inv -> inv.getArgument(0));

            Skill skill = service.createFromApp(USER_ID, originId, "desktop", "AgiMate Desktop",
                    declaration()).orElseThrow();

            assertEquals("app-desktop-3", skill.getName());
        }

        @Test
        @DisplayName("у подключения уже есть навык (даже удалённый) → второй не создаётся")
        void existingOriginBlocksCreation() {
            Skill deleted = originSkill(SkillUpdatedBy.APP);
            deleted.setDeletedAt(java.time.LocalDateTime.now());
            when(skillRepository.findByOriginConnectionId(originId)).thenReturn(Optional.of(deleted));

            assertTrue(service.createFromApp(USER_ID, originId, "desktop", "AgiMate Desktop",
                    declaration()).isEmpty());
            verify(skillRepository, never()).save(any());
        }

        @Test
        @DisplayName("гонка по уникальному origin — побеждает уже созданный навык")
        void originRaceReturnsWinner() {
            Skill winner = originSkill(SkillUpdatedBy.APP);
            when(skillRepository.findByOriginConnectionId(originId))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(winner));
            when(skillRepository.existsByKeyNotDeleted(USER_ID, "app-desktop", null)).thenReturn(false);
            when(skillRepository.save(any(Skill.class))).thenThrow(new DataIntegrityViolationException("origin"));

            Optional<Skill> result = service.createFromApp(USER_ID, originId, "desktop", "AgiMate Desktop",
                    declaration());

            assertSame(winner, result.orElseThrow());
        }

        @Test
        @DisplayName("приложение обновляет текст, пока его не правил пользователь")
        void appRefreshesUntouchedSkill() {
            Skill skill = originSkill(SkillUpdatedBy.APP);

            service.applyAppDeclaration(skill, "desktop", "AgiMate Desktop", declaration());

            assertEquals("New title", skill.getTitle());
            assertEquals("new description", skill.getDescription());
            assertEquals("new body", skill.getMdContent());
            assertEquals(List.of("do it"), skill.getStarters());
            assertEquals(2, skill.getVersion());
            verify(skillRepository).save(skill);
        }

        @Test
        @DisplayName("после правки пользователя приложение не перезаписывает текст")
        void userEditStopsRefresh() {
            Skill skill = originSkill(SkillUpdatedBy.USER);

            service.applyAppDeclaration(skill, "desktop", "AgiMate Desktop", declaration());

            assertEquals("old body", skill.getMdContent());
            assertEquals(1, skill.getVersion());
            verify(skillRepository, never()).save(any());
        }

        @Test
        @DisplayName("переименование app-навыка → 400")
        void renameForbidden() {
            when(skillRepository.findByIdNotDeleted(SKILL_ID)).thenReturn(Optional.of(originSkill(SkillUpdatedBy.APP)));

            BadRequestStatusException ex = assertThrows(BadRequestStatusException.class,
                    () -> service.update(SKILL_ID, USER_ID, false,
                            new UpdateSkillRequest(md("app-desktop-renamed", "connectors:\n  - app"), null)));
            assertTrue(ex.getMessage().contains("renamed"), ex.getMessage());
        }

        @Test
        @DisplayName("публикация app-навыка → 400")
        void publishingForbidden() {
            when(skillRepository.findByIdNotDeleted(SKILL_ID)).thenReturn(Optional.of(originSkill(SkillUpdatedBy.APP)));

            assertThrows(BadRequestStatusException.class,
                    () -> service.update(SKILL_ID, USER_ID, false,
                            new UpdateSkillRequest(md("app-desktop", "connectors:\n  - app"), true)));
        }

        @Test
        @DisplayName("другой коннектор в app-навыке → 400")
        void otherConnectorForbidden() {
            when(skillRepository.findByIdNotDeleted(SKILL_ID)).thenReturn(Optional.of(originSkill(SkillUpdatedBy.APP)));

            assertThrows(BadRequestStatusException.class,
                    () -> service.update(SKILL_ID, USER_ID, false,
                            new UpdateSkillRequest(md("app-desktop", "connectors:\n  - board"), null)));
        }

        @Test
        @DisplayName("app-навык нельзя оставить без коннектора app → 400")
        void droppingAppConnectorForbidden() {
            when(skillRepository.findByIdNotDeleted(SKILL_ID)).thenReturn(Optional.of(originSkill(SkillUpdatedBy.APP)));

            assertThrows(BadRequestStatusException.class,
                    () -> service.update(SKILL_ID, USER_ID, false,
                            new UpdateSkillRequest(withoutConnectors("app-desktop"), null)));
        }

        @Test
        @DisplayName("правка только правил доступа ставит updated_by=USER")
        void updateConnectorsMarksUserEdit() {
            when(skillRepository.findByIdNotDeleted(SKILL_ID)).thenReturn(Optional.of(originSkill(SkillUpdatedBy.APP)));
            when(skillRepository.save(any(Skill.class))).thenAnswer(inv -> inv.getArgument(0));

            service.updateConnectors(SKILL_ID, USER_ID, false, new UpdateSkillConnectorsRequest(
                    List.of(ConnectorRequirement.of("app")), null));

            verify(skillRepository).save(argThat(s -> s.getUpdatedBy() == SkillUpdatedBy.USER));
        }

        @Test
        @DisplayName("updateConnectors не обходит заморозку кода коннектора → 400")
        void updateConnectorsCannotEscapeFreeze() {
            when(skillRepository.findByIdNotDeleted(SKILL_ID)).thenReturn(Optional.of(originSkill(SkillUpdatedBy.APP)));

            assertThrows(BadRequestStatusException.class,
                    () -> service.updateConnectors(SKILL_ID, USER_ID, false, new UpdateSkillConnectorsRequest(
                            List.of(ConnectorRequirement.of("board")), null)));
        }

        @Test
        @DisplayName("полная правка пользователя разрешена и ставит updated_by=USER")
        void userEditIsAllowedAndMarked() {
            when(skillRepository.findByIdNotDeleted(SKILL_ID)).thenReturn(Optional.of(originSkill(SkillUpdatedBy.APP)));
            when(skillRepository.save(any(Skill.class))).thenAnswer(inv -> inv.getArgument(0));

            SkillResponse response = service.update(SKILL_ID, USER_ID, false,
                    new UpdateSkillRequest(md("app-desktop", "connectors:\n  - app"), null));

            assertEquals(2, response.version());
            verify(skillRepository).save(argThat(s -> s.getUpdatedBy() == SkillUpdatedBy.USER));
        }

        @Test
        @DisplayName("удаление приложения удаляет живой origin-навык")
        void deletesLiveOriginSkill() {
            Skill live = originSkill(SkillUpdatedBy.APP);
            when(skillRepository.findByOriginConnectionIdAndDeletedAtIsNull(originId)).thenReturn(Optional.of(live));
            when(skillRepository.findByIdNotDeleted(SKILL_ID)).thenReturn(Optional.of(live));

            service.deleteOriginSkill(originId, USER_ID);

            verify(skillRepository).softDelete(eq(SKILL_ID), any());
            verify(agentSkillRepository).deleteBySkillId(SKILL_ID);
        }

        @Test
        @DisplayName("уже удалённый origin-навык пропускается, удаление не падает")
        void skipsAlreadyDeletedOriginSkill() {
            when(skillRepository.findByOriginConnectionIdAndDeletedAtIsNull(originId)).thenReturn(Optional.empty());

            assertDoesNotThrow(() -> service.deleteOriginSkill(originId, USER_ID));

            verify(skillRepository, never()).softDelete(any(), any());
            verifyNoInteractions(agentSkillRepository);
        }

        @Test
        @DisplayName("деталь навыка отдаёт origin: код, имя, версию и устройство")
        void detailCarriesOrigin() {
            when(skillRepository.findByIdNotDeleted(SKILL_ID)).thenReturn(Optional.of(originSkill(SkillUpdatedBy.APP)));
            when(connectionRepository.findByIdInNotDeleted(any())).thenReturn(List.of(
                    Connection.builder().id(originId).connectorCode("app").build()));
            when(appRepository.findAllByIdInNotDeleted(any())).thenReturn(List.of(
                    App.builder().id(originId).code("desktop").title("AgiMate Desktop").version("1.4.2")
                            .info(Map.of("deviceName", "MacBook")).build()));

            SkillDetailResponse detail = service.getSkillDetail(SKILL_ID, USER_ID);

            assertEquals(originId, detail.origin().connectionId());
            assertEquals("desktop", detail.origin().code());
            assertEquals("AgiMate Desktop", detail.origin().title());
            assertEquals("1.4.2", detail.origin().version());
            assertEquals("MacBook", detail.origin().deviceName());
        }
    }
}
