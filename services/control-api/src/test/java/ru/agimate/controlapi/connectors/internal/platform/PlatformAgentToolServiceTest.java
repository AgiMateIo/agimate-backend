package ru.agimate.controlapi.connectors.internal.platform;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.data.domain.Pageable;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.ForbiddenStatusException;
import ru.agimate.controlapi.connectors.core.ConnectorEnv;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.entities.AgentSkill;
import ru.agimate.controlapi.database.entities.Skill;
import ru.agimate.controlapi.database.entities.StoredFile;
import ru.agimate.controlapi.database.enums.AgentType;
import ru.agimate.controlapi.database.enums.FileStatus;
import ru.agimate.controlapi.database.repositories.AgentRepository;
import ru.agimate.controlapi.database.repositories.AgentSkillRepository;
import ru.agimate.controlapi.database.repositories.SkillRepository;
import ru.agimate.controlapi.database.repositories.StoredFileRepository;
import ru.agimate.controlapi.service.AgentService;
import ru.agimate.controlapi.service.connection.ConnectionBindingService;
import ru.agimate.controlapi.service.AgentService.AgentCreateResult;
import ru.agimate.controlapi.service.AgentSkillService;
import ru.agimate.controlapi.service.SkillService;
import ru.agimate.controlapi.service.dto.agent.AgentCreateCommand;
import ru.agimate.controlapi.service.dto.agent.AgentUpdateCommand;
import ru.agimate.controlapi.service.file.UserFileService;
import ru.agimate.controlapi.storage.FileIds;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("PlatformAgentToolService")
class PlatformAgentToolServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID OTHER_USER_ID = UUID.randomUUID();
    private static final UUID SELF_AGENT_ID = UUID.randomUUID();
    private static final UUID OTHER_AGENT_ID = UUID.randomUUID();

    private final AgentRepository agentRepository = mock(AgentRepository.class);
    private final AgentSkillRepository agentSkillRepository = mock(AgentSkillRepository.class);
    private final SkillRepository skillRepository = mock(SkillRepository.class);
    private final StoredFileRepository storedFileRepository = mock(StoredFileRepository.class);
    private final AgentService agentService = mock(AgentService.class);
    private final SkillService skillService = mock(SkillService.class);
    private final AgentSkillService agentSkillService = mock(AgentSkillService.class);
    private final UserFileService userFileService = mock(UserFileService.class);

    private final ConnectionBindingService connectionBindingService = mock(ConnectionBindingService.class);

    private final PlatformAgentToolService agentTools = new PlatformAgentToolService(
            agentRepository, agentSkillRepository, skillRepository,
            storedFileRepository, agentService, skillService, agentSkillService, userFileService,
            connectionBindingService);
    private final PlatformConnectorService handler = new PlatformConnectorService(
            agentTools, mock(PlatformConnectionToolService.class), mock(PlatformLlmToolService.class),
            mock(PlatformWorkspaceToolService.class), mock(PlatformObservabilityToolService.class));

    PlatformAgentToolServiceTest() {
        ReflectionTestUtils.setField(agentTools, "frontendBaseUrl", "https://app.test");
    }

    /** Инициатор вызова = SELF_AGENT_ID: операции над ним должны блокироваться. */
    private static ConnectorEnv selfEnv() {
        return new ConnectorEnv(null, USER_ID, SELF_AGENT_ID, null, null, null, Map.of(), null);
    }

    /** Инициатор вызова = OTHER_AGENT_ID: операции над третьим агентом разрешены. */
    private static ConnectorEnv ownerEnv() {
        return new ConnectorEnv(null, USER_ID, OTHER_AGENT_ID, null, null, null, Map.of(), null);
    }

    private static ConnectorEnv sessionEnv(UUID sessionId) {
        return new ConnectorEnv(null, USER_ID, SELF_AGENT_ID, null, null, sessionId, Map.of(), null);
    }

    @Nested
    @DisplayName("list_files")
    class ListFiles {

        private final UUID sessionId = UUID.randomUUID();

        private StoredFile file() {
            StoredFile file = StoredFile.builder()
                    .id(UUID.randomUUID())
                    .userId(USER_ID)
                    .status(FileStatus.READY)
                    .mime("image/png")
                    .name("shot.png")
                    .sizeBytes(11L)
                    .expiresAt(LocalDateTime.now().plusDays(1))
                    .build();
            file.setCreatedAt(LocalDateTime.now());
            return file;
        }

        @Test
        @DisplayName("по умолчанию — файлы этого разговора, и агент получает agf_-идентификатор")
        void defaultsToCurrentConversation() {
            StoredFile stored = file();
            when(storedFileRepository.findVisible(eq(USER_ID), eq(null), eq(sessionId), eq(null),
                    any(LocalDateTime.class), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(stored)));

            // executeTool отдаёт запись коннектора картой — так её видит агент.
            Map<?, ?> result = (Map<?, ?>) handler.executeTool(sessionEnv(sessionId), "list_files", Map.of());

            List<?> files = (List<?>) result.get("files");
            assertEquals(1, files.size());
            assertEquals(FileIds.external(stored.getId()), ((Map<?, ?>) files.getFirst()).get("id"));
        }

        @Test
        @DisplayName("allConversations=true снимает сужение по разговору")
        void allConversationsDropsTheSessionFilter() {
            when(storedFileRepository.findVisible(eq(USER_ID), eq(null), eq(null), eq(null),
                    any(LocalDateTime.class), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of()));

            handler.executeTool(sessionEnv(sessionId), "list_files", Map.of("allConversations", true));

            verify(storedFileRepository).findVisible(eq(USER_ID), eq(null), eq(null), eq(null),
                    any(LocalDateTime.class), any(Pageable.class));
        }

        @Test
        @DisplayName("ран без канала — сессии нет, ищем по всему аккаунту, а не отказываем")
        void channellessRunFallsBackToAccount() {
            when(storedFileRepository.findVisible(eq(USER_ID), eq(null), eq(null), eq(null),
                    any(LocalDateTime.class), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of()));

            handler.executeTool(selfEnv(), "list_files", Map.of());

            verify(storedFileRepository).findVisible(eq(USER_ID), eq(null), eq(null), eq(null),
                    any(LocalDateTime.class), any(Pageable.class));
        }
    }

    @Nested
    @DisplayName("guard «не сам себя»")
    class SelfGuard {

        @Test
        @DisplayName("update_agent над собой — ConnectorException, сервис не вызывается")
        void updateSelfRejected() {
            var ex = assertThrows(ConnectorException.class, () -> handler.executeTool(
                    selfEnv(), "update_agent", Map.of("agentId", SELF_AGENT_ID.toString())));
            assertTrue(ex.getMessage().contains("itself"));
            verifyNoInteractions(agentService);
        }

        @Test
        @DisplayName("bind_skill над собой — ConnectorException")
        void bindSkillSelfRejected() {
            assertThrows(ConnectorException.class, () -> handler.executeTool(selfEnv(), "bind_skill",
                    Map.of("agentId", SELF_AGENT_ID.toString(), "skillId", UUID.randomUUID().toString())));
            verifyNoInteractions(agentSkillService);
        }

        @Test
        @DisplayName("unbind_skill над собой — ConnectorException")
        void unbindSkillSelfRejected() {
            assertThrows(ConnectorException.class, () -> handler.executeTool(selfEnv(), "unbind_skill",
                    Map.of("agentId", SELF_AGENT_ID.toString(), "skillId", UUID.randomUUID().toString())));
            verifyNoInteractions(agentSkillService);
        }

        @Test
        @DisplayName("mark_skills_installed над собой — ConnectorException")
        void markSkillsInstalledSelfRejected() {
            assertThrows(ConnectorException.class, () -> handler.executeTool(selfEnv(),
                    "mark_skills_installed", Map.of("agentId", SELF_AGENT_ID.toString())));
            verifyNoInteractions(agentSkillService);
        }

        @Test
        @DisplayName("list_agent_skills над собой — допустимо (read-only листинг, не управление)")
        void listAgentSkillsOnSelfIsAllowed() {
            when(agentRepository.findById(SELF_AGENT_ID)).thenReturn(Optional.of(Agent.builder()
                    .id(SELF_AGENT_ID).userId(USER_ID).name("self").type(AgentType.MCP).build()));
            when(agentSkillRepository.findByAgentId(SELF_AGENT_ID)).thenReturn(List.of());

            Map<?, ?> result = (Map<?, ?>) handler.executeTool(selfEnv(),
                    "list_agent_skills", Map.of("agentId", SELF_AGENT_ID.toString()));

            assertEquals(List.of(), result.get("skills"));
        }
    }

    @Nested
    @DisplayName("create_agent")
    class CreateAgent {

        @Test
        @DisplayName("WEBHOOK без webhookUrl — отказ сервиса приходит как ConnectorException")
        void webhookWithoutUrlIsTranslated() {
            when(agentService.create(eq(USER_ID), any(AgentCreateCommand.class)))
                    .thenThrow(new BadRequestStatusException("Webhook url is required when type is WEBHOOK"));

            var ex = assertThrows(ConnectorException.class, () -> handler.executeTool(selfEnv(),
                    "create_agent", Map.of("name", "Bot", "type", "WEBHOOK")));
            assertTrue(ex.getMessage().contains("Webhook url is required"));

            ArgumentCaptor<AgentCreateCommand> captor = ArgumentCaptor.forClass(AgentCreateCommand.class);
            verify(agentService).create(eq(USER_ID), captor.capture());
            AgentCreateCommand cmd = captor.getValue();
            assertEquals(AgentType.WEBHOOK, cmd.type());
            assertNull(cmd.webhookUrl());
        }

        @Test
        @DisplayName("MCP-агент возвращает ссылку на ключ, тип доходит до команды")
        void mcpCreateReturnsKeyUrl() {
            UUID agentId = UUID.randomUUID();
            Agent agent = Agent.builder().id(agentId).userId(USER_ID).name("MCP Bot")
                    .type(AgentType.MCP).build();
            when(agentService.create(eq(USER_ID), any(AgentCreateCommand.class)))
                    .thenReturn(new AgentCreateResult(agent, null, "plain-secret-key"));

            Map<?, ?> result = (Map<?, ?>) handler.executeTool(selfEnv(), "create_agent",
                    Map.of("name", "MCP Bot", "type", "MCP"));

            assertEquals(agentId.toString(), result.get("id"));
            assertEquals("MCP Bot", result.get("name"));
            assertEquals("https://app.test/dashboard/agents/" + agentId, result.get("keyUrl"));
            assertFalse(result.containsKey("plaintextKey"));

            ArgumentCaptor<AgentCreateCommand> captor = ArgumentCaptor.forClass(AgentCreateCommand.class);
            verify(agentService).create(eq(USER_ID), captor.capture());
            assertEquals(AgentType.MCP, captor.getValue().type());
        }

        @Test
        @DisplayName("без type уходит GENERIC (сервисный дефолт при null — CENTRIFUGO, тул обязан")
        void absentTypeDefaultsToGeneric() {
            UUID agentId = UUID.randomUUID();
            Agent agent = Agent.builder().id(agentId).userId(USER_ID).name("Bot")
                    .type(AgentType.GENERIC).build();
            when(agentService.create(eq(USER_ID), any(AgentCreateCommand.class)))
                    .thenReturn(new AgentCreateResult(agent, null, "key"));

            handler.executeTool(selfEnv(), "create_agent", Map.of("name", "Bot"));

            ArgumentCaptor<AgentCreateCommand> captor = ArgumentCaptor.forClass(AgentCreateCommand.class);
            verify(agentService).create(eq(USER_ID), captor.capture());
            assertEquals(AgentType.GENERIC, captor.getValue().type());
        }

        @Test
        @DisplayName("skillIds: внутренние коннекторы навыков открываются после создания")
        void skillIdsOpenInternalConnectors() {
            UUID agentId = UUID.randomUUID();
            Agent agent = Agent.builder().id(agentId).userId(USER_ID).name("Meta")
                    .type(AgentType.GENERIC).build();
            UUID skillId = UUID.randomUUID();
            when(agentService.create(eq(USER_ID), any(AgentCreateCommand.class)))
                    .thenReturn(new AgentCreateResult(agent, null, "key"));
            when(skillRepository.findByIdNotDeleted(skillId)).thenReturn(Optional.of(
                    Skill.builder().id(skillId).name("platform").connectorCodes(List.of("platform")).build()));
            when(connectionBindingService.kindOf("platform"))
                    .thenReturn(ConnectionBindingService.ConnectorKind.INTERNAL);

            handler.executeTool(selfEnv(), "create_agent",
                    Map.of("name", "Meta", "skillIds", List.of(skillId.toString())));

            verify(connectionBindingService).bindInternal(USER_ID, agentId, "platform");
        }

        @Test
        @DisplayName("skillIds: внешние коннекторы не открываются без выбора инстанса")
        void skillIdsLeaveExternalConnectorsClosed() {
            UUID agentId = UUID.randomUUID();
            Agent agent = Agent.builder().id(agentId).userId(USER_ID).name("Meta")
                    .type(AgentType.GENERIC).build();
            UUID skillId = UUID.randomUUID();
            when(agentService.create(eq(USER_ID), any(AgentCreateCommand.class)))
                    .thenReturn(new AgentCreateResult(agent, null, "key"));
            when(skillRepository.findByIdNotDeleted(skillId)).thenReturn(Optional.of(
                    Skill.builder().id(skillId).name("with-telegram")
                            .connectorCodes(List.of("telegram")).build()));
            when(connectionBindingService.kindOf("telegram"))
                    .thenReturn(ConnectionBindingService.ConnectorKind.EXTERNAL);

            handler.executeTool(selfEnv(), "create_agent",
                    Map.of("name", "Meta", "skillIds", List.of(skillId.toString())));

            verify(connectionBindingService, never()).bindInternal(any(), any(), any());
        }
    }

    @Nested
    @DisplayName("bind_skill")
    class BindSkill {

        @Test
        @DisplayName("внутренние коннекторы навыка открываются (как UI-флоу), внешние — нет")
        void opensInternalConnectorsOnly() {
            UUID agentId = UUID.randomUUID();
            UUID skillId = UUID.randomUUID();
            Skill skill = Skill.builder().id(skillId).name("meta")
                    .connectorCodes(List.of("platform", "telegram")).build();
            when(skillRepository.findByIdNotDeleted(skillId)).thenReturn(Optional.of(skill));
            when(connectionBindingService.kindOf("platform"))
                    .thenReturn(ConnectionBindingService.ConnectorKind.INTERNAL);
            when(connectionBindingService.kindOf("telegram"))
                    .thenReturn(ConnectionBindingService.ConnectorKind.EXTERNAL);

            Map<?, ?> result = (Map<?, ?>) handler.executeTool(selfEnv(), "bind_skill",
                    Map.of("agentId", agentId.toString(), "skillId", skillId.toString()));

            assertEquals(true, result.get("ok"));
            verify(agentSkillService).create(agentId, skillId, USER_ID);
            verify(connectionBindingService).bindInternal(USER_ID, agentId, "platform");
            verify(connectionBindingService, never()).bindInternal(eq(USER_ID), eq(agentId), eq("telegram"));
        }

        @Test
        @DisplayName("неизвестный коннектор навыка не роняет привязку")
        void unknownConnectorIsSkipped() {
            UUID agentId = UUID.randomUUID();
            UUID skillId = UUID.randomUUID();
            Skill skill = Skill.builder().id(skillId).name("legacy")
                    .connectorCodes(List.of("gone-connector")).build();
            when(skillRepository.findByIdNotDeleted(skillId)).thenReturn(Optional.of(skill));
            when(connectionBindingService.kindOf("gone-connector"))
                    .thenReturn(ConnectionBindingService.ConnectorKind.UNKNOWN);

            handler.executeTool(selfEnv(), "bind_skill",
                    Map.of("agentId", agentId.toString(), "skillId", skillId.toString()));

            verify(agentSkillService).create(agentId, skillId, USER_ID);
            verify(connectionBindingService, never()).bindInternal(any(), any(), any());
        }
    }

    @Nested
    @DisplayName("update_agent")
    class UpdateAgent {

        @Test
        @DisplayName("переименование уходит в patch одним полем — остальные не трогаются")
        void renameSendsOnlyTheName() {
            UUID agentId = UUID.randomUUID();
            when(agentRepository.findById(agentId)).thenReturn(Optional.of(Agent.builder()
                    .id(agentId).userId(USER_ID).name("Old").description("keep desc")
                    .instructions("keep prompt").type(AgentType.GENERIC).build()));
            when(agentSkillRepository.findByAgentId(agentId)).thenReturn(List.of());

            handler.executeTool(selfEnv(), "update_agent",
                    Map.of("agentId", agentId.toString(), "name", "New Name"));

            ArgumentCaptor<AgentUpdateCommand> captor = ArgumentCaptor.forClass(AgentUpdateCommand.class);
            verify(agentService).patch(eq(agentId), eq(USER_ID), captor.capture());
            AgentUpdateCommand cmd = captor.getValue();
            assertEquals("New Name", cmd.name());
            // Nothing else was passed, so patch leaves it alone — the tool no longer copies values back in
            assertNull(cmd.instructions());
            assertNull(cmd.description());
            assertNull(cmd.type());
        }

        @Test
        @DisplayName("type=«» трактуется как «не прислано» — тип не меняется (иначе WEBHOOK→GENERIC)")
        void blankTypeIsNotSent() {
            UUID agentId = UUID.randomUUID();
            when(agentRepository.findById(agentId)).thenReturn(Optional.of(Agent.builder()
                    .id(agentId).userId(USER_ID).name("Web").type(AgentType.WEBHOOK).build()));
            when(agentSkillRepository.findByAgentId(agentId)).thenReturn(List.of());

            handler.executeTool(selfEnv(), "update_agent",
                    Map.of("agentId", agentId.toString(), "type", ""));

            ArgumentCaptor<AgentUpdateCommand> captor = ArgumentCaptor.forClass(AgentUpdateCommand.class);
            verify(agentService).patch(eq(agentId), eq(USER_ID), captor.capture());
            assertNull(captor.getValue().type());
        }

        @Test
        @DisplayName("type=WEBHOOK + webhookUrl проходят в patch сырыми строками")
        void webhookFieldsPassThroughRaw() {
            UUID agentId = UUID.randomUUID();
            when(agentRepository.findById(agentId)).thenReturn(Optional.of(Agent.builder()
                    .id(agentId).userId(USER_ID).name("Old").type(AgentType.GENERIC).build()));
            when(agentSkillRepository.findByAgentId(agentId)).thenReturn(List.of());

            handler.executeTool(selfEnv(), "update_agent", Map.of(
                    "agentId", agentId.toString(),
                    "type", "WEBHOOK",
                    "webhookUrl", "https://hooks.example.com/x"));

            ArgumentCaptor<AgentUpdateCommand> captor = ArgumentCaptor.forClass(AgentUpdateCommand.class);
            verify(agentService).patch(eq(agentId), eq(USER_ID), captor.capture());
            AgentUpdateCommand cmd = captor.getValue();
            assertEquals(AgentType.WEBHOOK, cmd.type());
            assertEquals("https://hooks.example.com/x", cmd.webhookUrl());
            assertNull(cmd.webhookAuthHeader());
            assertNull(cmd.name());
        }
    }

    @Nested
    @DisplayName("delete_agent")
    class DeleteAgent {

        @Test
        @DisplayName("над собой — ConnectorException, сервис не вызывается")
        void selfRejected() {
            assertThrows(ConnectorException.class, () -> handler.executeTool(selfEnv(),
                    "delete_agent", Map.of("agentId", SELF_AGENT_ID.toString())));
            verifyNoInteractions(agentService);
        }

        @Test
        @DisplayName("над другим агентом — уходит в agentService.delete(id, userId)")
        void otherAgentDeleted() {
            UUID target = UUID.randomUUID();
            Map<?, ?> result = (Map<?, ?>) handler.executeTool(ownerEnv(), "delete_agent",
                    Map.of("agentId", target.toString()));

            verify(agentService).delete(target, USER_ID);
            assertEquals(true, result.get("ok"));
        }
    }

    @Nested
    @DisplayName("regenerate_agent_key")
    class RegenerateAgentKey {

        @Test
        @DisplayName("возвращает ссылку на новый ключ и вызывает regenerateKey")
        void regeneratesAndReturnsKeyUrl() {
            UUID agentId = UUID.randomUUID();
            Agent agent = Agent.builder().id(agentId).userId(USER_ID).name("Bot").build();
            when(agentService.regenerateKey(agentId, USER_ID))
                    .thenReturn(new AgentCreateResult(agent, null, "brand-new-key"));

            Map<?, ?> result = (Map<?, ?>) handler.executeTool(ownerEnv(), "regenerate_agent_key",
                    Map.of("agentId", agentId.toString()));

            verify(agentService).regenerateKey(agentId, USER_ID);
            assertEquals("https://app.test/dashboard/agents/" + agentId, result.get("keyUrl"));
            assertFalse(result.containsKey("plaintextKey"));
            assertEquals(agentId.toString(), result.get("id"));
            assertEquals("Bot", result.get("name"));
        }

        @Test
        @DisplayName("над собой — ConnectorException")
        void selfRejected() {
            assertThrows(ConnectorException.class, () -> handler.executeTool(selfEnv(),
                    "regenerate_agent_key", Map.of("agentId", SELF_AGENT_ID.toString())));
            verifyNoInteractions(agentService);
        }
    }

    @Nested
    @DisplayName("list_agent_skills")
    class ListAgentSkills {

        @Test
        @DisplayName("мапит навыки и считает satisfied по satisfiedSkillInstances")
        void mapsSkillsAndSatisfaction() {
            UUID agentId = UUID.randomUUID();
            when(agentRepository.findById(agentId)).thenReturn(Optional.of(Agent.builder()
                    .id(agentId).userId(USER_ID).name("Bot").type(AgentType.GENERIC).build()));
            UUID skillA = UUID.randomUUID();
            UUID skillB = UUID.randomUUID();
            when(agentSkillRepository.findByAgentId(agentId)).thenReturn(List.of(
                    AgentSkill.builder().id(UUID.randomUUID()).agentId(agentId).skillId(skillA).build(),
                    AgentSkill.builder().id(UUID.randomUUID()).agentId(agentId).skillId(skillB).build()));
            when(skillRepository.findByIdInNotDeleted(anyCollection())).thenReturn(List.of(
                    Skill.builder().id(skillA).name("Alpha").connectorCodes(List.of("telegram")).build(),
                    Skill.builder().id(skillB).name("Beta").connectorCodes(List.of("gmail")).build()));
            when(agentSkillService.satisfiedSkillInstances(agentId))
                    .thenReturn(Map.of(skillA, Set.of(UUID.randomUUID())));

            Map<?, ?> result = (Map<?, ?>) handler.executeTool(ownerEnv(), "list_agent_skills",
                    Map.of("agentId", agentId.toString()));

            List<?> skills = (List<?>) result.get("skills");
            assertEquals(2, skills.size());
            Map<?, ?> first = (Map<?, ?>) skills.get(0);
            Map<?, ?> second = (Map<?, ?>) skills.get(1);
            assertEquals(skillA.toString(), first.get("skillId"));
            assertEquals("Alpha", first.get("name"));
            assertEquals(true, first.get("satisfied"));
            assertEquals(skillB.toString(), second.get("skillId"));
            assertEquals(false, second.get("satisfied"));
            verify(agentSkillService).satisfiedSkillInstances(agentId);
        }
    }

    @Nested
    @DisplayName("delete_skill")
    class DeleteSkill {

        @Test
        @DisplayName("чужой публичный навык — доступен, но сервис отказывает (admin=false)")
        void publicNotOwnedSkillRejected() {
            UUID skillId = UUID.randomUUID();
            when(skillRepository.findByIdNotDeleted(skillId)).thenReturn(Optional.of(Skill.builder()
                    .id(skillId).userId(OTHER_USER_ID).name("Public").isPublic(true).build()));
            doThrow(new ForbiddenStatusException("Access denied"))
                    .when(skillService).delete(skillId, USER_ID, false);

            var ex = assertThrows(ConnectorException.class, () -> handler.executeTool(selfEnv(),
                    "delete_skill", Map.of("skillId", skillId.toString())));
            assertTrue(ex.getMessage().contains("Access denied"));
            verify(skillService).delete(skillId, USER_ID, false);
        }

        @Test
        @DisplayName("свой навык удаляется через non-admin путь")
        void ownSkillDeleted() {
            UUID skillId = UUID.randomUUID();
            when(skillRepository.findByIdNotDeleted(skillId)).thenReturn(Optional.of(Skill.builder()
                    .id(skillId).userId(USER_ID).name("Mine").build()));

            Map<?, ?> result = (Map<?, ?>) handler.executeTool(selfEnv(), "delete_skill",
                    Map.of("skillId", skillId.toString()));

            verify(skillService).delete(skillId, USER_ID, false);
            assertEquals(true, result.get("ok"));
        }
    }

    @Nested
    @DisplayName("delete_file")
    class DeleteFile {

        @Test
        @DisplayName("agf_-идентификатор уходит в UserFileService.delete")
        void passesAgfIdToUserFileService() {
            Map<?, ?> result = (Map<?, ?>) handler.executeTool(selfEnv(), "delete_file",
                    Map.of("fileId", "agf_12345"));

            verify(userFileService).delete(USER_ID, "agf_12345");
            assertEquals(true, result.get("ok"));
        }
    }

    @Nested
    @DisplayName("get_agent")
    class GetAgent {

        @Test
        @DisplayName("возвращает webhookUrl и hasWebhookAuth, но никогда значение заголовка")
        void echoesWebhookUrlAndAuthFlagOnly() {
            UUID agentId = UUID.randomUUID();
            when(agentRepository.findById(agentId)).thenReturn(Optional.of(Agent.builder()
                    .id(agentId).userId(USER_ID).name("Hook").type(AgentType.WEBHOOK)
                    .webhookUrl("https://hooks.example.com/wh")
                    .webhookAuthSecretId(UUID.randomUUID())
                    .build()));
            when(agentSkillRepository.findByAgentId(agentId)).thenReturn(List.of());

            Map<?, ?> result = (Map<?, ?>) handler.executeTool(ownerEnv(), "get_agent",
                    Map.of("agentId", agentId.toString()));

            assertEquals("https://hooks.example.com/wh", result.get("webhookUrl"));
            assertEquals(true, result.get("hasWebhookAuth"));
            assertFalse(result.containsKey("webhookAuthHeader"));
        }
    }
}
