package ru.agimate.mobileapi.controller;

import io.swagger.v3.oas.annotations.Operation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.agimate.common.rest.SuccessResponse;

import java.util.List;

/**
 * This Controller is used to handle requests from mobile devices
 */
@Slf4j
@RestController
@RequestMapping(DeviceActionController.PATH)
public class DeviceActionController {

    public static final String PATH = "/device/actions";

    @Operation(summary = "Returns tasks for mobile device")
    @GetMapping("/get")
    public SuccessResponse<List<String>> tasks() {
        return SuccessResponse.ok(List.of("one", "two"));
    }

}
