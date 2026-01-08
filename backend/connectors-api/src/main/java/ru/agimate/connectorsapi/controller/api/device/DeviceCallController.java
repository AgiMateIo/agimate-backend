package ru.agimate.connectorsapi.controller.api.device;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import ru.agimate.common.rest.ErrorResponse;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.connectorsapi.controller.api.device.dto.MobileActionRequest;
import ru.agimate.connectorsapi.service.MobileApiService;

@RestController
@RequestMapping(DeviceCallController.PATH)
@RequiredArgsConstructor
@Tag(name = "Device Call", description = "Execute device methods via API Key")
public class DeviceCallController {

    public static final String PATH = DeviceController.PATH + "/call/";

    private final MobileApiService mobileApiService;


    @Operation(
            summary = "Push action to device",
            description = "Sends an action to a specific mobile device via Centrifugo",
            security = @SecurityRequirement(name = "ApiKey")
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Action successfully pushed to device",
                    content = @Content(schema = @Schema(implementation = SuccessResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid request body",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "Invalid or missing API key",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    @PostMapping("/{deviceId}")
    public SuccessResponse<String> pushAction(
            @Parameter(
                    description = "Device identifier",
                    required = true,
                    example = "device-123"
            )
            @PathVariable String deviceId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "Action request with type and parameters",
                    required = true,
                    content = @Content(schema = @Schema(implementation = MobileActionRequest.class))
            )
            @Valid @RequestBody MobileActionRequest mobileActionRequest
    ) {
        mobileApiService.pushAction(deviceId, mobileActionRequest);
        return SuccessResponse.ok("success");
    }
}
