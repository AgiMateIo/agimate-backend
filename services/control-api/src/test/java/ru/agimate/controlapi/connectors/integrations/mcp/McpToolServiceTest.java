package ru.agimate.controlapi.connectors.integrations.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.controlapi.connectors.integrations.IntegrationEncryptionService;
import ru.agimate.controlapi.database.entities.IntegrationCredentials;
import ru.agimate.controlapi.database.entities.McpTool;
import ru.agimate.controlapi.database.repositories.IntegrationCredentialsRepository;
import ru.agimate.controlapi.database.repositories.McpToolRepository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("McpToolService")
class McpToolServiceTest {

    private static final UUID IDENTITY = UUID.randomUUID();

    @Mock
    private IntegrationCredentialsRepository credentialsRepository;
    @Mock
    private IntegrationEncryptionService encryptionService;
    @Mock
    private McpClient mcpClient;
    @Mock
    private McpToolRepository mcpToolRepository;

    private McpToolService service;

    @BeforeEach
    void setUp() {
        service = new McpToolService(credentialsRepository, encryptionService, mcpClient, mcpToolRepository);
    }

    private static JsonNode rawTool(String name) {
        return JsonUtils.toJsonNode("{\"name\":\"" + name + "\",\"description\":\"" + name + " desc\"}");
    }

    private static McpTool cached(String name) {
        return McpTool.builder().integrationCredentialsId(IDENTITY).name(name).build();
    }

    @Nested
    @DisplayName("discover")
    class Discover {

        @Test
        @DisplayName("MCP-экземпляр: снимает tools/list и мапит в строки кэша")
        void discoversMcpTools() {
            IntegrationCredentials creds = IntegrationCredentials.builder()
                    .connectorCode(McpConnectorService.CONNECTOR_CODE)
                    .encryptedData("enc")
                    .build();
            when(credentialsRepository.findByIdNotDeleted(IDENTITY)).thenReturn(Optional.of(creds));
            when(encryptionService.decryptCredentials("enc"))
                    .thenReturn(Map.of(McpUtils.FIELD_URL, "https://srv/mcp"));
            when(mcpClient.listTools(any())).thenReturn(List.of(rawTool("a"), rawTool("b")));

            List<McpTool> fresh = service.discover(IDENTITY);

            assertEquals(2, fresh.size());
            assertEquals("a", fresh.get(0).getName());
        }

        @Test
        @DisplayName("не MCP-коннектор: null, без обращения к серверу")
        void skipsNonMcp() {
            when(credentialsRepository.findByIdNotDeleted(IDENTITY)).thenReturn(Optional.of(
                    IntegrationCredentials.builder().connectorCode("telegram").build()));

            assertNull(service.discover(IDENTITY));
            verifyNoInteractions(mcpClient);
        }

        @Test
        @DisplayName("экземпляр не найден: null")
        void missingCredentials() {
            when(credentialsRepository.findByIdNotDeleted(IDENTITY)).thenReturn(Optional.empty());
            assertNull(service.discover(IDENTITY));
        }
    }

    @Nested
    @DisplayName("reconcile")
    class Reconcile {

        @Test
        @DisplayName("upsert новых/изменённых тулов + удаление пропавших")
        void upsertsAndDeletesStale() {
            when(mcpToolRepository.findByIntegrationCredentialsId(IDENTITY))
                    .thenReturn(List.of(cached("a"), cached("stale")));

            service.reconcile(IDENTITY, List.of(cached("a"), cached("b")));

            // a — обновлён (существовал), b — вставлен (новый)
            verify(mcpToolRepository).save(argThat(t -> t.getName().equals("a")));
            verify(mcpToolRepository).save(argThat(t -> t.getName().equals("b")));
            // stale — пропал из tools/list → удалён
            verify(mcpToolRepository).delete(argThat(t -> t.getName().equals("stale")));
        }

        @Test
        @DisplayName("пустой tools/list: всё существующее удаляется")
        void emptyListRemovesAll() {
            when(mcpToolRepository.findByIntegrationCredentialsId(IDENTITY))
                    .thenReturn(List.of(cached("a")));

            service.reconcile(IDENTITY, List.of());

            verify(mcpToolRepository).delete(argThat(t -> t.getName().equals("a")));
            verify(mcpToolRepository, never()).save(any());
        }
    }
}
