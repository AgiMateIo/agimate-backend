package ru.agimate.userapi.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.common.security.jwt.AgimateUserPrincipal;
import ru.agimate.userapi.controller.dto.response.ReferralResponse;
import ru.agimate.userapi.service.UserService;

import java.util.UUID;

/**
 * The caller's own invitation link. The path deliberately sits outside {@code /user/**}, which admits
 * GUEST as well: the security chain then falls through to the USER/ADMIN rule, and an account still
 * waiting for approval cannot hand out invitations of its own.
 */
@RestController
@RequestMapping("/referral")
@RequiredArgsConstructor
@Tag(name = "Referral", description = "The caller's referral code and how many people it brought")
public class ReferralController {

    private final UserService userService;

    @Operation(summary = "Get my referral code",
            description = "The code to append as ?ref= to the OAuth2 authorization URL, and the number "
                    + "of accounts created with it")
    @GetMapping
    public SuccessResponse<ReferralResponse> getMyReferral(@AuthenticationPrincipal AgimateUserPrincipal principal) {
        UUID userId = UUID.fromString(principal.id());
        String code = userService.findById(userId)
                .orElseThrow(() -> new NotFoundStatusException("User not found"))
                .getReferralCode();

        return SuccessResponse.ok(new ReferralResponse(code, userService.countInvited(userId)));
    }
}
