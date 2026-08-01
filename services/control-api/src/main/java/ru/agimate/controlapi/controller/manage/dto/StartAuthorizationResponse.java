package ru.agimate.controlapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Where to send the browser. Our own address is returned rather than the authorisation server's, so
 * that the {@code state} is born when the user actually goes to authorise — not when they filled in
 * the form and walked away.
 */
@Schema(description = "Authorization URL to open in the browser")
public record StartAuthorizationResponse(
        @Schema(description = "Absolute URL at the authorization server, with state and PKCE already in it")
        String authorizationUrl
) {}
