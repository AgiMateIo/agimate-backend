package ru.agimate.userapi.controller.dto.response.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.userapi.database.entities.OAuthProviderType;

/**
 * @param authorizationPath where to send the browser next, ticket included; relative to the service
 *                          so that the client keeps deciding which host it is talking to
 */
@Schema(description = "Provider Linking Ticket Response DTO")
public record LinkTicketResponse(
        @Schema(description = "One-time ticket, good for five minutes")
        String ticket,

        @Schema(description = "Provider it was issued for", example = "GOOGLE")
        OAuthProviderType provider,

        @Schema(description = "Where to send the browser, ticket included",
                example = "/user/oauth2/authorization/google?link_ticket=…")
        String authorizationPath
) {
    public LinkTicketResponse(String ticket, OAuthProviderType provider) {
        this(ticket, provider, "/user/oauth2/authorization/"
                + provider.name().toLowerCase(java.util.Locale.ROOT) + "?link_ticket=" + ticket);
    }
}
