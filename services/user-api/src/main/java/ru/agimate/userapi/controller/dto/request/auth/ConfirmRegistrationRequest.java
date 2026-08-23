package ru.agimate.userapi.controller.dto.request.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import ru.agimate.userapi.database.entities.AuthClient;

/**
 * @param client what the caller is, which decides the shape of the answer and nothing else — the
 *               same rule as on login. Null is read as {@code WEB}
 */
@Schema(description = "Registration Confirmation Request DTO")
public record ConfirmRegistrationRequest(
        @Schema(description = "Token from the letter", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        String token,

        @Schema(description = "Password for the new account, at least 8 characters",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Size(min = 8, max = 72)
        String password,

        @Schema(description = "WEB (default) or NATIVE", example = "WEB")
        AuthClient client,

        @Schema(description = "How this device should be named in the owner's device list",
                example = "Pixel 8")
        String deviceName
) {}
