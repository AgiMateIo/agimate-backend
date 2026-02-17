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
import ru.agimate.deviceapi.controller.api.dto.ToolUseRequest;
import ru.agimate.deviceapi.service.DeviceApiService;

@RestController
@RequestMapping(ApiDeviceCallApiController.PATH)
@RequiredArgsConstructor
@Tag(name = "Device Call", description = "Execute device methods via API Key")
public class ApiDeviceCallApiController {

    public static final String PATH = ApiDeviceApiController.PATH + "/call";

    private final DeviceApiService deviceApiService;

    @Operation(
            summary = "Push tool to device",
            description = "Sends a tool use request to a specific device via Centrifugo",
            security = @SecurityRequirement(name = "ApiKey")
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Tool successfully pushed to device",
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
    public SuccessResponse<String> pushTool(
            @Parameter(
                    description = "Device Auth key identifier",
                    required = true,
                    example = "device-123"
            )
            @PathVariable String deviceAuthKeyId,
            @Valid @RequestBody ToolUseRequest toolUseRequest
    ) {
        deviceApiService.pushTool(deviceAuthKeyId, toolUseRequest);
        return SuccessResponse.ok("success");
    }
}
