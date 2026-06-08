package ru.agimate.controlapi.controller.manage.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "Issued discovery probe code")
public record IssueProbeResponse(
        @Schema(description = "Probe code to include in a test message", example = "agm-probe-block-7f3kx9q2ab")
        String code,

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        @Schema(description = "Server time when the probe was issued; pass back as `since` to /probe/match")
        LocalDateTime issuedAt
) {}
