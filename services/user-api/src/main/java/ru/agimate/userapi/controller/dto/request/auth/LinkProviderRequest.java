package ru.agimate.userapi.controller.dto.request.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * @param proof what the linking round trip put on {@code link_proof} when it came back. It names a
 *              provider identity and no account — the account is the one this request is
 *              authenticated as, which is what keeps the binding out of reach of another origin
 */
@Schema(description = "Link Provider Request DTO")
public record LinkProviderRequest(
        @Schema(description = "The link_proof value the callback redirected with",
                example = "9f1c…", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        String proof
) {}
