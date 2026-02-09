package ru.agimate.deviceapi.controller.api;

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
import ru.agimate.deviceapi.controller.dto.request.MobileActionRequest;
import ru.agimate.deviceapi.service.InternalDeviceApiService;

@RestController
@RequestMapping(DeviceCallApiController.PATH)
@RequiredArgsConstructor
@Tag(name = "Device Call", description = "Execute device methods via API Key")
public class DeviceCallApiController {

    public static final String PATH = DeviceApiController.PATH + "/call";

    private final InternalDeviceApiService internalDeviceApiService;

    @Operation(
            summary = "Push action to device",
            description = "Sends an action to a specific device via Centrifugo",
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
    @PostMapping("/{deviceAuthKeyId}")
    public SuccessResponse<String> pushAction(
            @Parameter(
                    description = "Device Auth key identifier",
                    required = true,
                    example = "device-123"
            )
            @PathVariable String deviceAuthKeyId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "Action request with type and parameters",
                    required = true,
                    content = @Content(schema = @Schema(implementation = MobileActionRequest.class))
            )
            @Valid @RequestBody MobileActionRequest mobileActionRequest
    ) {
        internalDeviceApiService.pushAction(deviceAuthKeyId, mobileActionRequest);
        return SuccessResponse.ok("success");
    }
}
