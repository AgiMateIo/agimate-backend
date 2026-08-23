package ru.agimate.userapi.controller.dto.request.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param ref the referral code the visitor arrived with, if the frontend carried one from the
 *            landing page; an unknown code is dropped rather than refused
 */
@Schema(description = "Registration Request DTO")
public record RegisterRequest(
        @Schema(description = "Address the confirmation letter goes to", example = "user@example.com",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Email
        String email,

        @Schema(description = "Password of the account being asked for, at least 8 characters",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Size(min = 8, max = 72)
        String password,

        @Schema(description = "How the person wants to be addressed; the email is used when absent",
                example = "Eugene")
        @Size(max = 200)
        String displayName,

        @Schema(description = "Referral code from the link the visitor came by", example = "K7M2QX9F")
        @Size(max = 16)
        String ref
) {}
