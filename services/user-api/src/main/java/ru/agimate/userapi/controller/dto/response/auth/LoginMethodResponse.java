package ru.agimate.userapi.controller.dto.response.auth;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.userapi.database.entities.OAuthProviderType;
import ru.agimate.userapi.service.auth.LoginMethodService;

import java.time.LocalDateTime;

/**
 * @param provider null for the password entry
 * @param email    what the provider says the mailbox is; null when it says nothing, which a provider
 *                 bound by hand is allowed to do
 * @param addedAt  when the provider was bound, or when the password was last set
 */
@Schema(description = "Login Method Response DTO")
public record LoginMethodResponse(
        @Schema(description = "PASSWORD or OAUTH", example = "OAUTH")
        LoginMethodService.LoginMethod.Kind kind,

        @Schema(description = "Which provider, for an OAUTH entry", example = "GOOGLE")
        OAuthProviderType provider,

        @Schema(description = "Name to show for this way in", example = "Google")
        String title,

        @Schema(description = "Mailbox the provider reports, when it reports one")
        String email,

        @Schema(description = "When this way in appeared")
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        LocalDateTime addedAt
) {
    public static LoginMethodResponse of(LoginMethodService.LoginMethod method) {
        return new LoginMethodResponse(
                method.kind(),
                method.provider(),
                method.provider() == null ? "Password" : method.provider().getDisplayName(),
                method.email(),
                method.addedAt());
    }
}
