package ru.agimate.connectorsapi.controller.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "Summary of credentials for a connector")
public record ConnectorSummaryResponse(
        @Schema(description = "Connector code")
        String connectorCode,

        @Schema(description = "Connector display name")
        String connectorName,

        @Schema(description = "Number of credentials")
        long credentialCount,

        @Schema(description = "Last credential added")
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        LocalDateTime lastAddedAt,

        @Schema(description = "Last credential used")
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        LocalDateTime lastUsedAt
) {}
