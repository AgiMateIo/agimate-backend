package ru.agimate.deviceapi.controller.device;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.deviceapi.controller.device.dto.ToolResultRequest;
import ru.agimate.deviceapi.service.CentrifugoService;
import ru.agimate.deviceapi.service.DeviceApiService;
import ru.agimate.deviceapi.service.DeviceAuthKeyService;
import ru.agimate.deviceapi.service.ToolUseLogService;

import java.util.List;
import java.util.Map;

/**
 * This Controller is used to handle requests from devices
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping(DeviceToolsController.PATH)
public class DeviceToolsController {

    public static final String PATH = "/tools";

    private final DeviceAuthKeyService deviceAuthKeyService;
    private final CentrifugoService centrifugoService;
    private final DeviceApiService deviceApiService;
    private final ToolUseLogService toolUseLogService;

    @PostMapping("/result")
    public SuccessResponse<String> submitToolResult(
            @RequestBody @Valid
            ToolResultRequest toolResultRequest,
            Authentication authentication
    ) {
        log.info("Tool result received - {}", toolResultRequest.toString());

        var deviceAuthKey = deviceAuthKeyService.getDeviceAuthKey(authentication);

        String resultString = JsonUtils.toJson(toolResultRequest.result()).orElse(null);
        var toolUseLog = toolUseLogService.recordResult(deviceAuthKey, toolResultRequest.id(), resultString, null);
        deviceApiService.pushToAgent(toolUseLog.getApiKeyPubId().toString(), toolResultRequest);

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
