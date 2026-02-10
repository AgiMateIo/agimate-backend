package ru.agimate.userapi.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.rest.error.ForbiddenStatusException;
import ru.agimate.userapi.service.ServiceApiKeyService;

@RestController
@RequiredArgsConstructor
@Tag(name = "API Key Verification", description = "Verify API key validity")
public class ApiKeyVerifyController {

    private final ServiceApiKeyService serviceApiKeyService;

    @Operation(summary = "Verify API key validity")
    @PostMapping("/api-keys/verify")
    public SuccessResponse<Void> verifyApiKey(@RequestHeader("X-Api-Key") String apiKey) {
        serviceApiKeyService.validateKey(apiKey)
                .orElseThrow(() -> new ForbiddenStatusException("Invalid API key"));
        return SuccessResponse.empty();
    }
}
