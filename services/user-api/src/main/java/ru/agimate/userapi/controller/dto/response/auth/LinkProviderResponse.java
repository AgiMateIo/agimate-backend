package ru.agimate.userapi.controller.dto.response.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.userapi.database.entities.OAuthProviderType;
import ru.agimate.userapi.service.auth.LoginMethodService.LinkResult;
import ru.agimate.userapi.service.auth.ProviderIdentityService.LinkOutcome;

/**
 * @param outcome what became of the attempt. Only {@code TAKEN} and {@code PROVIDER_OCCUPIED} are
 *                refusals, and both are answered with 200 on purpose: nothing went wrong with the
 *                request, the account simply cannot have that provider, and the page needs to say
 *                which of the two it was
 */
@Schema(description = "Link Provider Response DTO")
public record LinkProviderResponse(
        @Schema(description = "Provider the proof was for", example = "GITHUB")
        OAuthProviderType provider,

        @Schema(description = "LINKED, ALREADY_YOURS, TAKEN or PROVIDER_OCCUPIED", example = "LINKED")
        LinkOutcome outcome
) {
    public static LinkProviderResponse of(LinkResult result) {
        return new LinkProviderResponse(result.provider(), result.outcome());
    }
}
