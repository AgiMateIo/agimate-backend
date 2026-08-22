package ru.agimate.controlapi.connectors.internal.platform;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;
import ru.agimate.controlapi.connectors.core.ConnectorEnv;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.connectors.core.ConnectorRegistry;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.entities.Connector;
import ru.agimate.controlapi.database.entities.StoredFile;
import ru.agimate.controlapi.database.enums.AgentType;
import ru.agimate.controlapi.database.enums.FileStatus;
import ru.agimate.controlapi.database.repositories.AgentRepository;
import ru.agimate.controlapi.database.repositories.AgentSkillRepository;
import ru.agimate.controlapi.database.repositories.ConnectionRepository;
import ru.agimate.controlapi.database.repositories.ConnectorRepository;
import ru.agimate.controlapi.database.repositories.SkillRepository;
import ru.agimate.controlapi.database.repositories.StoredFileRepository;
import ru.agimate.controlapi.service.AgentService;
import ru.agimate.controlapi.service.AgentSkillService;
import ru.agimate.controlapi.service.SkillService;
import ru.agimate.controlapi.service.connection.ConnectionBindingService;
import ru.agimate.controlapi.service.dto.agent.AgentUpdateCommand;
import ru.agimate.controlapi.service.tool.ToolDefinitionService;
import ru.agimate.controlapi.storage.FileIds;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("PlatformToolService")
class PlatformToolServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID SELF_AGENT_ID = UUID.randomUUID();

    private final ConnectorRepository connectorRepository = mock(ConnectorRepository.class);
    private final AgentRepository agentRepository = mock(AgentRepository.class);
    private final AgentSkillRepository agentSkillRepository = mock(AgentSkillRepository.class);
    private final AgentService agentService = mock(AgentService.class);
    private final AgentSkillService agentSkillService = mock(AgentSkillService.class);
    private final ConnectionBindingService connectionBindingService = mock(ConnectionBindingService.class);
    private final StoredFileRepository storedFileRepository = mock(StoredFileRepository.class);

    private final PlatformToolService toolService = new PlatformToolService(
            mock(ConnectorRegistry.class), connectorRepository, mock(ToolDefinitionService.class),
            mock(SkillRepository.class), mock(SkillService.class), agentRepository,
            agentSkillRepository, agentSkillService, agentService,
            mock(ConnectionRepository.class), connectionBindingService, storedFileRepository);
    private final PlatformConnectorService handler = new PlatformConnectorService(toolService);

    PlatformToolServiceTest() {
        ReflectionTestUtils.setField(toolService, "frontendBaseUrl", "https://app.test");
    }

    /** Инициатор вызова = SELF_AGENT_ID: операции над ним должны блокироваться. */
    private static ConnectorEnv selfEnv() {
        return new ConnectorEnv(null, USER_ID, SELF_AGENT_ID, null, null, null, Map.of(), null);
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
        @DisplayName("bind_connection над собой — ConnectorException")
        void bindConnectionSelfRejected() {
            assertThrows(ConnectorException.class, () -> handler.executeTool(selfEnv(), "bind_connection",
                    Map.of("agentId", SELF_AGENT_ID.toString(), "connectionId", UUID.randomUUID().toString())));
            verifyNoInteractions(connectionBindingService);
        }
    }

    @Nested
    @DisplayName("create_connection (deep-link)")
    class CreateConnection {

        @Test
        @DisplayName("integration-коннектор → setup_required + setupUrl, без записи в БД")
        void integrationReturnsSetupLink() {
            Connector telegram = mock(Connector.class);
            when(telegram.isIntegration()).thenReturn(true);
            when(connectorRepository.findById("telegram")).thenReturn(Optional.of(telegram));

            Map<String, Object> result = handler.executeTool(selfEnv(), "create_connection",
                    Map.of("connectorCode", "telegram", "name", "My Bot"));

            assertEquals("setup_required", result.get("status"));
            assertEquals("telegram", result.get("connectorCode"));
            String url = (String) result.get("setupUrl");
            assertTrue(url.startsWith("https://app.test/connections/new?connector=telegram"));
            assertTrue(url.contains("name=My+Bot"));
        }

        @Test
        @DisplayName("не-integration коннектор → ConnectorException")
        void nonIntegrationRejected() {
            when(connectorRepository.findById("board")).thenReturn(Optional.empty());
            assertThrows(ConnectorException.class, () -> handler.executeTool(selfEnv(),
                    "create_connection", Map.of("connectorCode", "board")));
        }
    }

    @Nested
    @DisplayName("create_agent")
    class CreateAgent {

        @Test
        @DisplayName("type=WEBHOOK отклоняется (webhook настраивается в UI)")
        void webhookTypeRejected() {
            var ex = assertThrows(ConnectorException.class, () -> handler.executeTool(selfEnv(),
                    "create_agent", Map.of("name", "Bot", "type", "WEBHOOK")));
            assertTrue(ex.getMessage().contains("WEBHOOK"));
            verifyNoInteractions(agentService);
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
            when(agentSkillRepository.findByAgentId(agentId)).thenReturn(java.util.List.of());

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
    }
}
