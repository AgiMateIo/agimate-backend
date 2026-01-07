package ru.agimate.connectorsapi.controller.api.dto.wildberries;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Request for getting Wildberries new orders (GET request - no body parameters)")
public class WildberriesGetOrdersRequest {
    // Empty - GET request doesn't need body parameters
    // All parameters will be in query string if needed
}
