package ru.agimate.controlapi.controller.mcp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import ru.agimate.controlapi.controller.mcp.dto.EmptyResult;
import ru.agimate.controlapi.controller.mcp.dto.JsonRpcError;
import ru.agimate.controlapi.controller.mcp.dto.JsonRpcRequest;
import ru.agimate.controlapi.controller.mcp.dto.JsonRpcResponse;
import ru.agimate.controlapi.security.AgentPrincipal;
import ru.agimate.controlapi.service.mcp.McpService;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The HTTP status the revision pins to protocol errors. The service answers in JSON-RPC and knows
 * nothing about HTTP, so this is the only layer where a wrong status can be caught.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("McpController — HTTP-статусы ревизии 2026-07-28")
class McpControllerTest {

    private static final AgentPrincipal PRINCIPAL =
            new AgentPrincipal("mcp-agent", UUID.randomUUID(), UUID.randomUUID());
    private static final JsonRpcRequest REQUEST = new JsonRpcRequest("2.0", 1, "tools/list", null);

    @Mock
    private McpService mcpService;

    private McpController controller;

    @BeforeEach
    void setUp() {
        controller = new McpController(mcpService);
    }

    @Nested
    @DisplayName("версия протокола")
    class ProtocolVersion {

        @Test
        @DisplayName("чужая версия → 400 и -32022 со списком поддерживаемых и запрошенной")
        void unsupportedVersion() {
            ResponseEntity<JsonRpcResponse> response = controller.handle(REQUEST, "2025-06-18", PRINCIPAL);

            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
            JsonRpcError error = response.getBody().error();
            assertEquals(-32022, error.code());
            assertEquals(Map.of("supported", List.of("2026-07-28"), "requested", "2025-06-18"), error.data());
            verifyNoInteractions(mcpService);
        }

        @Test
        @DisplayName("заголовок без версии пропускается: живые клиенты открываются initialize без него")
        void missingHeaderIsServed() {
            when(mcpService.handle(any(), any()))
                    .thenReturn(Optional.of(JsonRpcResponse.ok(1, EmptyResult.INSTANCE)));

            assertEquals(HttpStatus.OK, controller.handle(REQUEST, null, PRINCIPAL).getStatusCode());
        }
    }

    @Nested
    @DisplayName("ответ сервиса")
    class ServiceResponse {

        @Test
        @DisplayName("отсутствующая капабилити клиента → 400, тело — JSON-RPC ошибка")
        void missingCapabilityIsBadRequest() {
            when(mcpService.handle(any(), any())).thenReturn(Optional.of(JsonRpcResponse.error(1,
                    JsonRpcError.MISSING_REQUIRED_CLIENT_CAPABILITY, "Missing required client capability")));

            ResponseEntity<JsonRpcResponse> response = controller.handle(REQUEST, "2026-07-28", PRINCIPAL);

            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
            assertNotNull(response.getBody().error());
        }

        @Test
        @DisplayName("прочие ошибки остаются 200: модель должна прочитать ответ, а не упасть на транспорте")
        void otherErrorsStayOk() {
            when(mcpService.handle(any(), any())).thenReturn(Optional.of(
                    JsonRpcResponse.error(1, JsonRpcError.INVALID_PARAMS, "taskId is required")));

            assertEquals(HttpStatus.OK, controller.handle(REQUEST, "2026-07-28", PRINCIPAL).getStatusCode());
        }

        @Test
        @DisplayName("нотификация → 202 без тела")
        void notificationIsAccepted() {
            when(mcpService.handle(any(), any())).thenReturn(Optional.empty());

            ResponseEntity<JsonRpcResponse> response = controller.handle(REQUEST, null, PRINCIPAL);

            assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
            assertNull(response.getBody());
        }
    }
}
