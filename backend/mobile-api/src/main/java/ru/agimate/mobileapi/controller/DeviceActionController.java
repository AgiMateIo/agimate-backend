package ru.agimate.mobileapi.controller;

import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.mobileapi.service.CentrifugoService;

import java.util.List;
import java.util.Map;

/**
 * This Controller is used to handle requests from mobile devices
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping(DeviceActionController.PATH)
public class DeviceActionController {

    public static final String PATH = "/device/actions";

    private final CentrifugoService centrifugoService;

    @Operation(summary = "Returns tasks for mobile device")
    @GetMapping("/get")
    public SuccessResponse<List<String>> tasks() {
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
