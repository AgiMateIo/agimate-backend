package ru.agimate.controlapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * What the front picked out of the return URL. The error travels here too instead of being rendered
 * in place: RFC 9207 forbids showing the authorisation server's text when {@code iss} does not match
 * the recorded issuer, and the front has no recorded issuer to compare against.
 */
@Schema(description = "Authorization response brought back by the browser")
public record CompleteAuthorizationRequest(
        @NotBlank
        @Schema(description = "The state issued when the flow started")
        String state,

        @Schema(description = "Authorization code; absent when the server returned an error")
        String code,

        @Schema(description = "Error code from the authorization server, e.g. access_denied")
        String error,

        @Schema(description = "iss parameter of the authorization response (RFC 9207), if the server sent one")
        String iss
) {}
