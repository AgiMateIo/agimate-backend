package ru.agimate.deviceapi.controller.app;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.deviceapi.controller.app.dto.ToolResultRequest;
import ru.agimate.deviceapi.service.AgentDeliveryService;
import ru.agimate.deviceapi.service.AppService;
import ru.agimate.deviceapi.service.ToolUseLogService;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping(AppToolsController.PATH)
public class AppToolsController {

    public static final String PATH = AppRegistrationController.PATH + "/tools";

    private final AppService appService;
    private final AgentDeliveryService agentDeliveryService;
    private final ToolUseLogService toolUseLogService;

    @PostMapping("/result")
    public SuccessResponse<String> submitToolResult(
            @RequestBody @Valid
            ToolResultRequest toolResultRequest,
            Authentication authentication
    ) {
        log.info("Tool result received - {}", toolResultRequest.toString());

        var app = appService.getApp(authentication);

        var toolUseLog = toolUseLogService.recordOutput(app, toolResultRequest);
        agentDeliveryService.deliverToolResult(toolUseLog.getAgentId(), toolResultRequest);

        return SuccessResponse.empty();
    }

}
