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
import ru.agimate.connectorsapi.controller.dto.wildberries.WildberriesGetCardsRequest;
import ru.agimate.connectorsapi.controller.dto.wildberries.WildberriesGetCardsResponse;
import ru.agimate.connectorsapi.controller.dto.wildberries.WildberriesGetOrdersRequest;
import ru.agimate.connectorsapi.controller.dto.wildberries.WildberriesGetOrdersResponse;
import ru.agimate.connectorsapi.service.WildberriesCallService;

import java.util.UUID;

@RestController
@RequestMapping("/api/call/wildberries")
@RequiredArgsConstructor
@Tag(name = "Wildberries", description = "Wildberries marketplace connector - execute WB API methods")
public class WildberriesCallController {

    private final WildberriesCallService wildberriesCallService;

    @Operation(
            summary = "Get product cards",
            description = "Returns list of product cards from Wildberries seller account. " +
                    "Uses cursor-based pagination for retrieving next pages of results.",
            security = @SecurityRequirement(name = "ApiKey")
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Successfully retrieved product cards",
                    content = @Content(schema = @Schema(implementation = WildberriesGetCardsResponse.class))
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
    @PostMapping("/getCards")
    public SuccessResponse<WildberriesGetCardsResponse> getCards(
            @Parameter(
                    description = "Credential ID (UUID) for Wildberries connector",
                    required = true,
                    example = "550e8400-e29b-41d4-a716-446655440000"
            )
            @RequestParam UUID credentialId,
            @Valid @RequestBody WildberriesGetCardsRequest request
    ) {
        return SuccessResponse.ok(wildberriesCallService.getCards(credentialId, request));
    }

    @Operation(
            summary = "Get new orders",
            description = "Returns list of new orders from Wildberries. " +
                    "This is a GET request that retrieves all new orders for the seller.",
            security = @SecurityRequirement(name = "ApiKey")
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Successfully retrieved orders",
                    content = @Content(schema = @Schema(implementation = WildberriesGetOrdersResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid credential or disabled credential",
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
    @GetMapping("/getOrders")
    public SuccessResponse<WildberriesGetOrdersResponse> getOrders(
            @Parameter(
                    description = "Credential ID (UUID) for Wildberries connector",
                    required = true,
                    example = "550e8400-e29b-41d4-a716-446655440000"
            )
            @RequestParam UUID credentialId
    ) {
        // For GET request, create empty request object
        return SuccessResponse.ok(wildberriesCallService.getOrders(credentialId, new WildberriesGetOrdersRequest()));
    }
}
