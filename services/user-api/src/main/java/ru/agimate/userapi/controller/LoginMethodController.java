package ru.agimate.userapi.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.security.jwt.AgimateUserPrincipal;
import ru.agimate.userapi.controller.dto.response.auth.LinkTicketResponse;
import ru.agimate.userapi.controller.dto.response.auth.LoginMethodResponse;
import ru.agimate.userapi.database.entities.OAuthProviderType;
import ru.agimate.userapi.service.auth.LoginMethodService;

import java.util.List;
import java.util.UUID;

/**
 * The ways into one's own account: what they are, how another is added, how one is dropped.
 *
 * <p>Adding a provider by hand is for the case the login cannot serve: signing in through a provider
 * joins accounts by verified address, so somebody whose GitHub sits on a different mailbox used to
 * get a second account instead of a second way into the first one.
 *
 * <p>Open to GUEST as well, like the device list — an account still waiting for approval has the
 * same reason to manage how it is reached.
 */
@RestController
@RequestMapping(LoginMethodController.PATH)
@RequiredArgsConstructor
@Tag(name = "Login methods", description = "Providers and password bound to the current account")
public class LoginMethodController {

    public static final String PATH = "/auth/methods";

    private final LoginMethodService loginMethodService;

    @Operation(summary = "List my ways in",
            description = "Every provider bound to the account, oldest first, and the password if "
                    + "there is one")
    @GetMapping("/")
    public SuccessResponse<List<LoginMethodResponse>> listMethods(
            @AuthenticationPrincipal AgimateUserPrincipal principal
    ) {
        List<LoginMethodResponse> methods = loginMethodService.list(UUID.fromString(principal.id()))
                .stream()
                .map(LoginMethodResponse::of)
                .toList();

        return SuccessResponse.ok(methods);
    }

    @Operation(summary = "Start binding a provider",
            description = "Answers with a one-time ticket. Send the browser to "
                    + "/user/oauth2/authorization/{provider}?link_ticket=… and it comes back with the "
                    + "provider bound to this account — whatever address that provider reports.")
    @PostMapping("/link/{provider}")
    public SuccessResponse<LinkTicketResponse> startLinking(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @Parameter(description = "Provider to bind", required = true)
            @PathVariable("provider") OAuthProviderType provider
    ) {
        String ticket = loginMethodService.issueLinkTicket(UUID.fromString(principal.id()));
        return SuccessResponse.ok(new LinkTicketResponse(ticket, provider));
    }

    @Operation(summary = "Unbind a provider",
            description = "Refused when it is the only way into the account")
    @DeleteMapping("/oauth/{provider}")
    public SuccessResponse<String> unlinkProvider(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @Parameter(description = "Provider to unbind", required = true)
            @PathVariable("provider") OAuthProviderType provider
    ) {
        loginMethodService.unlinkProvider(UUID.fromString(principal.id()), provider);
        return SuccessResponse.ok("success");
    }

    @Operation(summary = "Remove the password",
            description = "Leaves the providers as the way in. Refused when it is the only one")
    @DeleteMapping("/password")
    public SuccessResponse<String> removePassword(
            @AuthenticationPrincipal AgimateUserPrincipal principal
    ) {
        loginMethodService.dropPassword(UUID.fromString(principal.id()));
        return SuccessResponse.ok("success");
    }
}
