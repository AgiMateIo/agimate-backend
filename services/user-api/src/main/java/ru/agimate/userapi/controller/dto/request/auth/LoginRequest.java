package ru.agimate.userapi.controller.dto.request.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import ru.agimate.userapi.database.entities.AuthClient;

/**
 * @param client what the caller is, which decides the shape of the answer and nothing else: a web
 *               client's refresh token belongs in a cookie, a native one's in the body. Null is read
 *               as {@code WEB}
 */
@Schema(description = "Password Login Request DTO")
public record LoginRequest(
        @Schema(description = "Email of the account", example = "user@example.com",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Email
        String email,

        @Schema(description = "The account's password", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        String password,

        @Schema(description = "WEB (default) or NATIVE", example = "WEB")
        AuthClient client,

        @Schema(description = "How this device should be named in the owner's device list",
                example = "Pixel 8")
        String deviceName
) {}
