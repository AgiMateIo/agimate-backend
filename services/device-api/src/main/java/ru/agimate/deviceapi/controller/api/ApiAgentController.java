package ru.agimate.deviceapi.controller.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.security.apikey.ApiKeyPrincipal;
import ru.agimate.deviceapi.controller.api.dto.AgentConfigResponse;
import ru.agimate.deviceapi.service.AgentService;

import java.util.UUID;

@RestController
@RequestMapping(ApiAgentController.PATH)
@RequiredArgsConstructor
@Tag(name = "Agent API", description = "Agent configuration via API Key")
public class ApiAgentController {

    public static final String PATH = ApiConnectorsController.PATH + "/agent";

    private final AgentService agentService;

    @Operation(
            summary = "Get agent configuration",
            description = "Returns agent configuration for the authenticated API key"
    )
    @GetMapping("/settings")
    public SuccessResponse<AgentConfigResponse> getAgentSettings(
            @AuthenticationPrincipal ApiKeyPrincipal principal
    ) {
        UUID apiKeyPubId = UUID.fromString(principal.pubId());
        return SuccessResponse.ok(agentService.getConfigByApiKeyPubId(apiKeyPubId));
    }
}
