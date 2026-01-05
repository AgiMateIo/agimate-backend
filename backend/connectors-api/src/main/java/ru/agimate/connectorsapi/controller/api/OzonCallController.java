package ru.agimate.connectorsapi.controller.api;

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
import ru.agimate.connectorsapi.controller.dto.ozon.OzonGetProductInfoRequest;
import ru.agimate.connectorsapi.controller.dto.ozon.OzonGetProductInfoResponse;
import ru.agimate.connectorsapi.controller.dto.ozon.OzonGetProductListRequest;
import ru.agimate.connectorsapi.controller.dto.ozon.OzonGetProductListResponse;
import ru.agimate.connectorsapi.service.OzonCallService;

import java.util.UUID;

@RestController
@RequestMapping("/api/call/ozon")
@RequiredArgsConstructor
@Tag(name = "Ozon", description = "Ozon marketplace connector - execute Ozon API methods")
public class OzonCallController {

    private final OzonCallService ozonCallService;

    @Operation(
            summary = "Get product list",
            description = "Returns paginated list of seller products from Ozon marketplace. " +
                    "Uses cursor-based pagination with lastId parameter for retrieving next pages.",
            security = @SecurityRequirement(name = "ApiKey")
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Successfully retrieved product list",
                    content = @Content(schema = @Schema(implementation = OzonGetProductListResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid request parameters or disabled credential",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "Invalid or missing API key",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Credential not found or access denied",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    @PostMapping("/getProductList")
    public SuccessResponse<OzonGetProductListResponse> getProductList(
            @Parameter(
                    description = "Credential ID (UUID) for Ozon connector",
                    required = true,
                    example = "550e8400-e29b-41d4-a716-446655440000"
            )
            @RequestParam UUID credentialId,
            @Valid @RequestBody OzonGetProductListRequest request
    ) {
        return SuccessResponse.ok(ozonCallService.getProductList(credentialId, request));
    }

    @Operation(
            summary = "Get product information",
            description = "Returns detailed information about specific Ozon product by its identifier. " +
                    "Product ID must be valid Ozon product identifier from your seller account.",
            security = @SecurityRequirement(name = "ApiKey")
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Successfully retrieved product information",
                    content = @Content(schema = @Schema(implementation = OzonGetProductInfoResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid product ID or disabled credential",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "Invalid or missing API key",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Credential not found or access denied",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    @PostMapping("/getProductInfo")
    public SuccessResponse<OzonGetProductInfoResponse> getProductInfo(
            @Parameter(
                    description = "Credential ID (UUID) for Ozon connector",
                    required = true,
                    example = "550e8400-e29b-41d4-a716-446655440000"
            )
            @RequestParam UUID credentialId,
            @Valid @RequestBody OzonGetProductInfoRequest request
    ) {
        return SuccessResponse.ok(ozonCallService.getProductInfo(credentialId, request));
    }
}
