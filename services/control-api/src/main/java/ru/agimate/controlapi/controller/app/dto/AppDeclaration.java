package ru.agimate.controlapi.controller.app.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * How the app names itself at link time: the kind code is a free string (the platform keeps no
 * catalogue of apps), and the title/version are what the user and support see.
 */
@Schema(description = "The app's own identity: code, title and client version")
public record AppDeclaration(
        @NotBlank
        @Size(max = 48)
        @Schema(description = "App kind in kebab-case: desktop, chromium, photoshop", example = "desktop")
        String code,

        @Size(max = 200)
        @Schema(description = "Human-readable app title", example = "AgiMate Desktop")
        String title,

        @NotBlank
        @Size(max = 64)
        @Schema(description = "Client version", example = "1.4.2")
        String version
) {}
