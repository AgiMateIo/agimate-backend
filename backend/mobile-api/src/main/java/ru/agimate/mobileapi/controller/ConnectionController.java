package ru.agimate.mobileapi.controller;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.websocket.server.PathParam;
import org.springframework.web.bind.annotation.*;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.mobileapi.controller.dto.request.AddConnectionRequest;
import ru.agimate.mobileapi.controller.dto.request.TriggerRequest;

import java.util.List;

/**
 * This Controller is used to handle requests from mobile devices
 */
@RestController
@RequestMapping("/connection")
public class ConnectionController {

    @Operation(summary = "Returns tasks for mobile device")
    @GetMapping("/tasks")
    public SuccessResponse<List<String>> tasks() {
        return SuccessResponse.ok(List.of("one", "two"));
    }

    @Operation(summary = "Handle trigger from device")
    @PostMapping("/trigger")
    public SuccessResponse<String> trigger(
            @RequestBody
            TriggerRequest triggerRequest
    ) {
        // TODO: implement later
        return SuccessResponse.ok(triggerRequest.name());
    }

}
