package ru.agimate.mobileapi.controller;

import io.swagger.v3.oas.annotations.Operation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.mobileapi.controller.dto.request.TriggerRequest;

import java.util.List;

/**
 * This Controller is used to handle requests from mobile devices
 */
@Slf4j
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
        // ***REMOVED***
        log.info("Trigger - {} {}", triggerRequest.name(), triggerRequest.data().toString());
        return SuccessResponse.ok(triggerRequest.name());
    }

}
