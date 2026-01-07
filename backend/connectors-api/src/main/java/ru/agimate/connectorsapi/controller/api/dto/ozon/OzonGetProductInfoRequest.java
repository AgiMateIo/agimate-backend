package ru.agimate.connectorsapi.controller.api.dto.ozon;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request for getting detailed Ozon product information")
public class OzonGetProductInfoRequest {

    @Schema(
            description = "Product identifier in Ozon system",
            example = "123456789",
            required = true
    )
    @NotNull(message = "Product ID is required")
    private Integer productId;
}
