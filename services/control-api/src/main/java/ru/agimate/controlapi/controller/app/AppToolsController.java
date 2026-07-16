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
import ru.agimate.controlapi.service.dto.ToolResult;
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

        var toolCallLog = toolCallLogService.recordOutputFromDevice(app, toolResultRequest);

        // Устройство прислало результат под PK лога; агенту доставляем под его external_id — агент
        // (и gRPC-поллинг воркера) корреллируют вызовы в собственном пространстве идентификаторов.
        var agentResult = new ToolResult(
                toolCallLog.getExternalId(),
                toolCallLog.getConnectorCode(),
                toolCallLog.getOutput(),
                toolCallLog.getError());
        agentDeliveryService.deliverToolResult(toolCallLog.getAgentId(), agentResult);

        return SuccessResponse.empty();
    }

}
