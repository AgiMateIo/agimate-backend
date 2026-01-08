package ru.agimate.connectorsapi.controller.api.connectors.ozon.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Response from Ozon product list API")
public class OzonGetProductListResponse {

    @Schema(description = "Response data from Ozon API - structure matches Ozon documentation")
    private Map<String, Object> result;

    @Schema(description = "Execution duration in milliseconds")
    private Long durationMs;
}
