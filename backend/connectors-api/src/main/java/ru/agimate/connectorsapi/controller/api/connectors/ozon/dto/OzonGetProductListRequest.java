package ru.agimate.connectorsapi.controller.api.connectors.ozon.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request for getting Ozon product list")
public class OzonGetProductListRequest {

    @Schema(
            description = "Number of products per page",
            example = "100",
            defaultValue = "100",
            minimum = "1",
            maximum = "1000"
    )
    @Min(1)
    @Max(1000)
    @Builder.Default
    private Integer limit = 100;

    @Schema(
            description = "ID of the last product for pagination (cursor-based)",
            example = "12345",
            nullable = true
    )
    private String lastId;
}
