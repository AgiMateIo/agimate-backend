package ru.agimate.controlapi.controller.manage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.security.jwt.AgimateUserPrincipal;
import ru.agimate.controlapi.controller.manage.dto.mcp.McpAuthorizationParams;
import ru.agimate.controlapi.controller.manage.dto.mcp.McpConsentApproveRequest;
import ru.agimate.controlapi.controller.manage.dto.mcp.McpConsentResponse;
import ru.agimate.controlapi.controller.manage.dto.mcp.McpRedirectResponse;
import ru.agimate.controlapi.service.mcp.oauth.CheckOutcome;
import ru.agimate.controlapi.service.mcp.oauth.McpAuthorizationService;

import java.util.UUID;

/**
 * The backend half of the MCP server's authorization endpoint; the other half is the consent page on
 * the front, which keeps no state and sends the whole request with every call
 * (docs/decisions/mcp-server-oauth.md). A 400 here means the page shows the error itself and never
 * redirects: the client or its redirect_uri could not be verified.
 */
@RestController
@RequestMapping(ManageMcpOAuthController.PATH)
@RequiredArgsConstructor
@Tag(name = "MCP OAuth", description = "Consent for external MCP clients connecting to an agent")
public class ManageMcpOAuthController {

    public static final String PATH = "/manage/mcp-oauth";

    private final McpAuthorizationService authorizationService;

    @Operation(summary = "Validate an authorization request",
            description = "Returns the client and the user's agents, or a redirect carrying an error back to the client")
    @PostMapping("/authorize/check")
    public SuccessResponse<McpConsentResponse> check(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @RequestBody McpAuthorizationParams params
    ) {
        UUID userId = UUID.fromString(principal.id());
        return SuccessResponse.ok(switch (authorizationService.check(userId, params.toRequest())) {
            case CheckOutcome.Consent consent -> new McpConsentResponse(null, consent.view());
            case CheckOutcome.Redirect redirect -> new McpConsentResponse(redirect.redirectUrl(), null);
        });
    }

    @Operation(summary = "Give consent", description = "Returns the client's redirect_uri with an authorization code")
    @PostMapping("/authorize/approve")
    public SuccessResponse<McpRedirectResponse> approve(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @Valid @RequestBody McpConsentApproveRequest request
    ) {
        UUID userId = UUID.fromString(principal.id());
        return SuccessResponse.ok(new McpRedirectResponse(
                authorizationService.approve(userId, request.authorization().toRequest(), request.agentId())));
    }

    @Operation(summary = "Refuse consent", description = "Returns the client's redirect_uri with error=access_denied")
    @PostMapping("/authorize/deny")
    public SuccessResponse<McpRedirectResponse> deny(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @RequestBody McpAuthorizationParams params
    ) {
        UUID userId = UUID.fromString(principal.id());
        return SuccessResponse.ok(new McpRedirectResponse(authorizationService.deny(userId, params.toRequest())));
    }
}
