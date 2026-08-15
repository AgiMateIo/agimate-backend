package ru.agimate.userapi.controller.dto.request.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * The native half of the login: what the application sends once the redirect has handed it a code.
 *
 * @param redirectUri the address the code was delivered to, repeated here so that the exchange is
 *                    bound to the same one the login started with
 */
@Schema(description = "Native Token Exchange Request DTO")
public record NativeTokenRequest(
        @Schema(description = "One-time code from the redirect",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        String code,

        @Schema(description = "PKCE verifier whose S256 hash is the challenge the login started with",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        String codeVerifier,

        @Schema(description = "Redirect address the code arrived at", example = "agimate://auth",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        String redirectUri,

        @Schema(description = "How this device should be named in the owner's device list",
                example = "Pixel 8")
        String deviceName
) {}
