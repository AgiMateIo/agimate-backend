package ru.agimate.connectorsapi.controller.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.connectorsapi.controller.dto.request.CallMethodRequest;
import ru.agimate.connectorsapi.controller.dto.response.CallResultResponse;
import ru.agimate.connectorsapi.service.CallService;

@RestController
@RequestMapping(CallController.PATH)
@RequiredArgsConstructor
@Tag(name = "Call", description = "Execute connector methods")
public class CallController {

    public static final String PATH = "/call";

    private final CallService callService;

    @Operation(summary = "Call a connector method")
    @PostMapping("/{connectorCode}/{methodName}")
    public SuccessResponse<CallResultResponse> callMethod(
            @PathVariable String connectorCode,
            @PathVariable String methodName,
            @Valid @RequestBody CallMethodRequest request
    ) {
        return SuccessResponse.ok(callService.executeMethod(connectorCode, methodName, request));
    }
}
