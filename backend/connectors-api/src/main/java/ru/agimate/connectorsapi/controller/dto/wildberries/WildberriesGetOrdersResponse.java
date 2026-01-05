package ru.agimate.connectorsapi.controller.dto.wildberries;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Response from Wildberries new orders API")
public class WildberriesGetOrdersResponse {

    @Schema(description = "Response data from Wildberries API - structure matches WB documentation")
    private Map<String, Object> result;

    @Schema(description = "Execution duration in milliseconds")
    private Long durationMs;
}
