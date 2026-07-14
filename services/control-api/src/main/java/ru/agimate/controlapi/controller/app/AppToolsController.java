package ru.agimate.controlapi.controller.app;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.controlapi.controller.app.dto.ToolResultRequest;
import ru.agimate.controlapi.security.AppPrincipal;
import ru.agimate.controlapi.service.AgentDeliveryService;
import ru.agimate.controlapi.service.AppService;
import ru.agimate.controlapi.service.tool.ToolCallLogService;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping(AppToolsController.PATH)
public class AppToolsController {

    public static final String PATH = AppRegistrationController.PATH + "/tools";

    private final AppService appService;
    private final AgentDeliveryService agentDeliveryService;
    private final ToolCallLogService toolCallLogService;

    @PostMapping("/result")
    public SuccessResponse<String> submitToolResult(
            @RequestBody @Valid
            ToolResultRequest toolResultRequest,
            @AuthenticationPrincipal AppPrincipal principal
    ) {
        log.info("Tool result received - {}", toolResultRequest.toString());

        var app = appService.getApp(principal);

        var toolCallLog = toolCallLogService.recordOutput(app, toolResultRequest);
        agentDeliveryService.deliverToolResult(toolCallLog.getAgentId(), toolResultRequest);

        return SuccessResponse.empty();
    }

}
