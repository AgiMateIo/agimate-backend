package ru.agimate.controlapi.controller.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.controlapi.controller.agent.dto.ToolResultResponse;
import ru.agimate.controlapi.controller.agent.dto.ToolResultStatus;
import ru.agimate.controlapi.database.entities.ToolCallLog;
import ru.agimate.controlapi.security.AgentPrincipal;
import ru.agimate.controlapi.service.tool.AgentToolCallService;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("AgentToolCallController.getToolResult")
class AgentToolCallControllerTest {

    private final AgentToolCallService service = mock(AgentToolCallService.class);
    private final AgentToolCallController controller = new AgentToolCallController(service);

    private static final UUID AGENT = UUID.randomUUID();
    private static final AgentPrincipal PRINCIPAL = new AgentPrincipal("agent", AGENT, UUID.randomUUID());

    private ToolResultResponse result(ToolCallLog log) {
        when(service.getToolCallLog(AGENT, "tc1")).thenReturn(log);
        SuccessResponse<ToolResultResponse> response = controller.getToolResult("tc1", PRINCIPAL);
        return response.getResponse();
    }

    @Test
    @DisplayName("не завершён (finishAt=null) → PENDING без result/error")
    void pending() {
        ToolResultResponse r = result(ToolCallLog.builder().build());
        assertEquals(ToolResultStatus.PENDING, r.status());
        assertNull(r.result());
        assertNull(r.error());
    }

    @Test
    @DisplayName("завершён с ошибкой → ERROR c сообщением, без result (не ErrorResponse в 200)")
    void error() {
        ToolResultResponse r = result(ToolCallLog.builder()
                .finishAt(LocalDateTime.now())
                .error("boom")
                .build());
        assertEquals(ToolResultStatus.ERROR, r.status());
        assertEquals("boom", r.error());
        assertNull(r.result());
    }

    @Test
    @DisplayName("завершён успешно → SUCCESS c output в result")
    void success() {
        ToolResultResponse r = result(ToolCallLog.builder()
                .finishAt(LocalDateTime.now())
                .output("{\"x\":1}")
                .build());
        assertEquals(ToolResultStatus.SUCCESS, r.status());
        assertEquals("{\"x\":1}", r.result());
        assertNull(r.error());
    }
}
