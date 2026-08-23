package ru.agimate.userapi.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.rest.error.ServiceUnavailableStatusException;
import ru.agimate.common.security.jwt.AgimateUserPrincipal;
import ru.agimate.userapi.config.OAuthProperties;
import ru.agimate.userapi.controller.dto.request.auth.ChangePasswordRequest;
import ru.agimate.userapi.controller.dto.request.auth.ConfirmRegistrationRequest;
import ru.agimate.userapi.controller.dto.request.auth.ForgotPasswordRequest;
import ru.agimate.userapi.controller.dto.request.auth.LoginRequest;
import ru.agimate.userapi.controller.dto.request.auth.RegisterRequest;
import ru.agimate.userapi.controller.dto.request.auth.ResendConfirmationRequest;
import ru.agimate.userapi.controller.dto.request.auth.ResetPasswordRequest;
import ru.agimate.userapi.controller.dto.response.auth.AuthResponse;
import ru.agimate.userapi.database.entities.AuthClient;
import ru.agimate.userapi.mappers.AuthMapper;
import ru.agimate.userapi.security.jwt.RefreshTokenService;
import ru.agimate.userapi.service.auth.IssuedTokens;
import ru.agimate.userapi.service.auth.PasswordAuthService;
import ru.agimate.userapi.service.auth.RegistrationService;
import ru.agimate.userapi.service.mail.MailService;

import java.util.UUID;

/**
 * Signing in with a password, and everything that leads to having one. Sits beside {@code /oauth2}
 * rather than inside it: this is another way of proving who you are, not a variety of OAuth.
 *
 * <p>Both ways end in the same place — a row in the session registry — so a person may hold a browser
 * session opened by Google and a phone session opened by a password at the same time, and the device
 * list shows them side by side.
 */
@RestController
@RequestMapping(AuthController.PATH)
@RequiredArgsConstructor
@Tag(name = "Password auth", description = "Signing in with a password, and setting one by mail")
public class AuthController {

    public static final String PATH = "/auth";

    private final PasswordAuthService passwordAuthService;
    private final RegistrationService registrationService;
    private final RefreshTokenService refreshTokenService;
    private final OAuthProperties oAuthProperties;
    private final MailService mailService;

    @Operation(summary = "Sign in with a password",
            description = "An unknown address and a wrong password are refused identically. Web "
                    + "callers receive the refresh token as an httpOnly cookie, native callers in "
                    + "the body — the client says which it is.")
    @PostMapping("/login")
    public SuccessResponse<AuthResponse> login(
            HttpServletRequest request,
            HttpServletResponse response,
            @Valid @RequestBody
            LoginRequest loginRequest
    ) {
        AuthClient client = loginRequest.client() == null ? AuthClient.WEB : loginRequest.client();

        IssuedTokens tokens = passwordAuthService.login(loginRequest.email(), loginRequest.password(),
                client, deviceLabel(request, loginRequest.deviceName()));

        return respond(request, response, tokens, client);
    }

    @Operation(summary = "Register with a password",
            description = "Takes the request and sends a confirmation letter. The answer is the same "
                    + "whether the address is free, already has an account, or has had enough letters "
                    + "for one hour — nothing here tells the caller who is registered.")
    @PostMapping("/register")
    public SuccessResponse<String> register(
            HttpServletRequest request,
            @Valid @RequestBody
            RegisterRequest registerRequest
    ) {
        requireMail();
        registrationService.register(registerRequest.email(), registerRequest.displayName(),
                registerRequest.ref(), oAuthProperties.frontendOrigin(request));
        return SuccessResponse.ok("If the address can be registered, a letter is on its way");
    }

    @Operation(summary = "Confirm the registration",
            description = "The account is created here and not before: an unconfirmed address must "
                    + "not hold one. Ends signed in — the person has just proved the mailbox.")
    @PostMapping("/register/confirm")
    public SuccessResponse<AuthResponse> confirmRegistration(
            HttpServletRequest request,
            HttpServletResponse response,
            @Valid @RequestBody
            ConfirmRegistrationRequest confirmRequest
    ) {
        AuthClient client = confirmRequest.client() == null ? AuthClient.WEB : confirmRequest.client();
        IssuedTokens tokens = registrationService.confirm(confirmRequest.token(),
                confirmRequest.password(), client, deviceLabel(request, confirmRequest.deviceName()));

        return respond(request, response, tokens, client);
    }

    @Operation(summary = "Send the confirmation letter again",
            description = "For a registration that is still waiting. Answers the same way whether or "
                    + "not there was one.")
    @PostMapping("/register/resend")
    public SuccessResponse<String> resendConfirmation(
            HttpServletRequest request,
            @Valid @RequestBody
            ResendConfirmationRequest resendRequest
    ) {
        requireMail();
        registrationService.resend(resendRequest.email(), oAuthProperties.frontendOrigin(request));
        return SuccessResponse.ok("If a registration is waiting for the address, a letter is on its way");
    }

    @Operation(summary = "Ask for a password by mail",
            description = "Sends a link that sets the password — for an account that forgot one and "
                    + "for an account that never had one alike. The answer is the same whether or "
                    + "not an account with this address exists.")
    @PostMapping("/password/forgot")
    public SuccessResponse<String> forgotPassword(
            HttpServletRequest request,
            @Valid @RequestBody
            ForgotPasswordRequest forgotRequest
    ) {
        requireMail();
        passwordAuthService.requestReset(forgotRequest.email(), oAuthProperties.frontendOrigin(request));
        return SuccessResponse.ok("If the address belongs to an account, a letter is on its way");
    }

    @Operation(summary = "Set the password with a token from the letter",
            description = "Ends every session of the account: whoever else knew the old password "
                    + "holds tokens that would otherwise outlive it.")
    @PostMapping("/password/reset")
    public SuccessResponse<String> resetPassword(
            @Valid @RequestBody
            ResetPasswordRequest resetRequest
    ) {
        passwordAuthService.reset(resetRequest.token(), resetRequest.password());
        return SuccessResponse.ok("success");
    }

    @Operation(summary = "Change the password",
            description = "Requires the current one. Every other session of the account ends; this "
                    + "one keeps working.")
    @PostMapping("/password/change")
    public SuccessResponse<String> changePassword(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @Valid @RequestBody
            ChangePasswordRequest changeRequest
    ) {
        passwordAuthService.change(UUID.fromString(principal.id()), principal.authSessionId(),
                changeRequest.currentPassword(), changeRequest.newPassword());
        return SuccessResponse.ok("success");
    }

    /**
     * Where the refresh token goes. A browser's belongs in the httpOnly cookie and only there; an
     * installed application has no cookie jar the answer could have written to, so it is told.
     */
    private SuccessResponse<AuthResponse> respond(HttpServletRequest request,
                                                  HttpServletResponse response,
                                                  IssuedTokens tokens, AuthClient client) {
        if (client == AuthClient.NATIVE) {
            return SuccessResponse.ok(AuthMapper.forNative(tokens));
        }

        OAuthProperties.ResolvedDomain resolved = oAuthProperties.resolveFromRequest(request);
        refreshTokenService.setHttpOnlyRefreshTokenCookie(response, tokens.refreshToken(),
                resolved.cookieDomain(), resolved.cookieSecure());
        return SuccessResponse.ok(AuthMapper.forWeb(tokens));
    }

    /**
     * An installation with no mail configured cannot finish any of these, and saying so is the whole
     * point: without this the request is accepted, a row is written and a cheerful "a letter is on
     * its way" comes back, while the only trace of the truth is a warning in the log of an async
     * executor. Confirming a link that did arrive keeps working — nothing is sent from there.
     */
    private void requireMail() {
        if (!mailService.isConfigured()) {
            throw new ServiceUnavailableStatusException(
                    "This installation cannot send mail, so password sign-in is not available here");
        }
    }

    /** The label is shown back to its owner in the device list and trusted for nothing else. */
    private static String deviceLabel(HttpServletRequest request, String deviceName) {
        return StringUtils.hasText(deviceName) ? deviceName : request.getHeader("User-Agent");
    }
}
