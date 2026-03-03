package ru.agimate.deviceapi.controller.app;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.deviceapi.controller.app.dto.ToolResultRequest;
import ru.agimate.deviceapi.service.ConnectorApiService;
import ru.agimate.deviceapi.service.ConnectorService;
import ru.agimate.deviceapi.service.ToolUseLogService;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping(AppToolsController.PATH)
public class AppToolsController {

    public static final String PATH = AppRegistrationController.PATH + "/tools";

    private final ConnectorService connectorService;
    private final ConnectorApiService connectorApiService;
    private final ToolUseLogService toolUseLogService;

    @PostMapping("/result")
    public SuccessResponse<String> submitToolResult(
            @RequestBody @Valid
            ToolResultRequest toolResultRequest,
            Authentication authentication
    ) {
        log.info("Tool result received - {}", toolResultRequest.toString());

        var connector = connectorService.getConnector(authentication);

        String resultString = JsonUtils.toJson(toolResultRequest.result()).orElse(null);
        var toolUseLog = toolUseLogService.recordResult(connector, toolResultRequest.id(), resultString, null);
        connectorApiService.pushToAgent(toolUseLog.getApiKeyPubId().toString(), toolResultRequest);

        return SuccessResponse.empty();
    }

}
