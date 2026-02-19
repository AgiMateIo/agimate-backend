package ru.agimate.deviceapi.controller.app;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.deviceapi.controller.app.dto.ToolResultRequest;
import ru.agimate.deviceapi.service.AppApiService;
import ru.agimate.deviceapi.service.AppService;
import ru.agimate.deviceapi.service.CentrifugoService;
import ru.agimate.deviceapi.service.ToolUseLogService;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping(DeviceToolsController.PATH)
public class DeviceToolsController {

    public static final String PATH = "/tools";

    private final AppService appService;
    private final CentrifugoService centrifugoService;
    private final AppApiService appApiService;
    private final ToolUseLogService toolUseLogService;

    @PostMapping("/result")
    public SuccessResponse<String> submitToolResult(
            @RequestBody @Valid
            ToolResultRequest toolResultRequest,
            Authentication authentication
    ) {
        log.info("Tool result received - {}", toolResultRequest.toString());

        var app = appService.getApp(authentication);

        String resultString = JsonUtils.toJson(toolResultRequest.result()).orElse(null);
        var toolUseLog = toolUseLogService.recordResult(app, toolResultRequest.id(), resultString, null);
        appApiService.pushToAgent(toolUseLog.getApiKeyPubId().toString(), toolResultRequest);

        return SuccessResponse.empty();
    }

    @Operation(
            summary = "Get pending tools for device",
            description = "Returns list of pending tools that device should execute"
    )
    @GetMapping("/get")
    public SuccessResponse<List<String>> getPendingTools() {
        return SuccessResponse.ok(List.of("one", "two"));
    }

    @Operation(summary = "Test endpoint - publishes test message to Centrifugo")
    @GetMapping("/test")
    public SuccessResponse<Map<String, Object>> test() {
        log.info("Test endpoint called, publishing to Centrifugo");
        Map<String, Object> message = centrifugoService.createTestMessage();
        centrifugoService.publishMessage("test-channel", message);
        return SuccessResponse.ok(message);
    }

}
