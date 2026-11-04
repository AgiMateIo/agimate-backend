package ru.agimate.controlapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * Where a skill came from: the app instance that brought it. The instance is also the selector — the
 * skill resolves to exactly this connection, never to another app.
 */
@Schema(description = "The app instance a skill originated from")
public record SkillOriginResponse(
        @Schema(description = "The connection (app instance) the skill belongs to")
        UUID connectionId,

        @Schema(description = "App kind the client declared", example = "desktop")
        String code,

        @Schema(description = "Human-readable app title", example = "AgiMate Desktop")
        String title,

        @Schema(description = "Client version", example = "1.4.2")
        String version,

        @Schema(description = "Device name the app declared at link time")
        String deviceName
) {}
