package ru.agimate.userapi.controller.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "The caller's referral code and its result so far")
public record ReferralResponse(
        @Schema(description = "Append to the authorization URL as ?ref=", example = "K7M2QX9F")
        String code,

        @Schema(description = "Accounts created with this code", example = "3")
        long invitedCount
) {
}
