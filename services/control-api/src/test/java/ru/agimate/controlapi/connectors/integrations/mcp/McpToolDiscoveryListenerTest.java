package ru.agimate.controlapi.connectors.integrations.mcp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.agimate.controlapi.connectors.core.events.ConnectorCreatedEvent;
import ru.agimate.controlapi.connectors.core.events.ConnectorDeletedEvent;
import ru.agimate.controlapi.connectors.core.events.ConnectorModifiedEvent;
import ru.agimate.controlapi.database.entities.ConnectionTool;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("McpToolDiscoveryListener")
class McpToolDiscoveryListenerTest {

    private static final UUID IDENTITY = UUID.randomUUID();
    private static final String IDENTITY_STR = IDENTITY.toString();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final String MCP = McpConnectorService.CONNECTOR_CODE;

    @Mock
    private McpToolService mcpToolService;

    private McpToolDiscoveryListener listener;

    @BeforeEach
    void setUp() {
        listener = new McpToolDiscoveryListener(mcpToolService);
    }

    @Test
    @DisplayName("created (mcp): discover → reconcile")
    void onCreated() {
        List<ConnectionTool> fresh = List.of(ConnectionTool.builder().name("a").build());
        when(mcpToolService.discover(IDENTITY)).thenReturn(fresh);

        listener.onCreated(new ConnectorCreatedEvent(MCP, IDENTITY_STR, USER_ID));

        verify(mcpToolService).discover(IDENTITY);
        verify(mcpToolService).reconcile(eq(IDENTITY), eq(fresh));
    }

    @Test
    @DisplayName("modified (mcp): пересинк тулов")
    void onModified() {
        when(mcpToolService.discover(IDENTITY)).thenReturn(List.of());

        listener.onModified(new ConnectorModifiedEvent(MCP, IDENTITY_STR, USER_ID));

        verify(mcpToolService).reconcile(eq(IDENTITY), eq(List.of()));
    }

    @Test
    @DisplayName("deleted (mcp): чистка кэша по identity")
    void onDeleted() {
        listener.onDeleted(new ConnectorDeletedEvent(MCP, IDENTITY_STR));

        verify(mcpToolService).deleteByIdentity(IDENTITY);
    }

    @Test
    @DisplayName("не MCP-коннектор: полностью игнорируется")
    void ignoresNonMcp() {
        listener.onCreated(new ConnectorCreatedEvent("telegram", IDENTITY_STR, USER_ID));
        listener.onModified(new ConnectorModifiedEvent("telegram", IDENTITY_STR, USER_ID));
        listener.onDeleted(new ConnectorDeletedEvent("telegram", IDENTITY_STR));

        verifyNoInteractions(mcpToolService);
    }
}
