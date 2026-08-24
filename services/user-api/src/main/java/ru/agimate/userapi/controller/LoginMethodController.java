package ru.agimate.userapi.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.security.jwt.AgimateUserPrincipal;
import ru.agimate.userapi.controller.dto.request.auth.LinkProviderRequest;
import ru.agimate.userapi.controller.dto.response.auth.LinkProviderResponse;
import ru.agimate.userapi.controller.dto.response.auth.LoginMethodResponse;
import ru.agimate.userapi.database.entities.OAuthProviderType;
import ru.agimate.userapi.service.auth.LoginMethodService;

import java.util.List;
import java.util.UUID;

/**
 * The ways into one's own account: what they are, how another is added, how one is dropped.
 *
 * <p>Adding a provider by hand is for the case the sign-in cannot serve: it joins accounts by
 * verified address, so somebody whose GitHub sits on a different mailbox used to get a second
 * account instead of a second way into the first one.
 *
 * <p>Binding takes two steps and has to. The client sends the browser to
 * {@code /user/oauth2/authorization/{provider}?link=1&redirect_to=…}; the callback comes back to
 * {@code redirect_to} with {@code link_proof} and {@code provider} on the query string, and the
 * client posts that proof here. The account is named by this request and by nothing earlier, so a
 * round trip somebody else caused cannot bind anything to anybody.
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

    @Operation(summary = "Finish binding a provider",
            description = "Takes the link_proof the callback came back with and binds that provider "
                    + "to the account this request is authenticated as. Answers 200 with the "
                    + "outcome: LINKED, ALREADY_YOURS, or a refusal — TAKEN when that account of "
                    + "the provider belongs to somebody else, PROVIDER_OCCUPIED when this account "
                    + "already reaches the provider through another of its accounts.")
    @PostMapping("/link")
    public SuccessResponse<LinkProviderResponse> linkProvider(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @Valid @RequestBody
            LinkProviderRequest linkRequest
    ) {
        return SuccessResponse.ok(LinkProviderResponse.of(loginMethodService.redeemLinkProof(
                UUID.fromString(principal.id()), linkRequest.proof())));
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
