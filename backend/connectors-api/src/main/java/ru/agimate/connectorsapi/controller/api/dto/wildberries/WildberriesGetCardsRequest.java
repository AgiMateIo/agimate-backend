package ru.agimate.connectorsapi.controller.api.dto.wildberries;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request for getting Wildberries product cards")
public class WildberriesGetCardsRequest {

    @Schema(
            description = "Number of product cards per page",
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
            description = "Cursor object for pagination - contains state from previous request",
            example = "{\"updatedAt\": \"2024-01-01T00:00:00Z\", \"nmID\": 12345}",
            nullable = true
    )
    private Map<String, Object> cursor;
}
