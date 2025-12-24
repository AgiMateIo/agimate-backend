package ru.agimate.connectorsapi.controller.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.connectorsapi.connector.ConnectorMethod;
import ru.agimate.connectorsapi.connector.ConnectorMethodParameter;

import java.util.List;

@Schema(description = "Connector method information")
public record MethodResponse(
        @Schema(description = "Method name (identifier)")
        String name,

        @Schema(description = "Method display name")
        String displayName,

        @Schema(description = "Method description")
        String description,

        @Schema(description = "HTTP method (GET, POST, PUT, DELETE)")
        String httpMethod,

        @Schema(description = "API endpoint")
        String endpoint,

        @Schema(description = "Method category")
        String category,

        @Schema(description = "Method parameters")
        List<MethodParameterResponse> parameters
) {
    public static MethodResponse from(ConnectorMethod method) {
        return new MethodResponse(
                method.name(),
                method.displayName(),
                method.description(),
                method.httpMethod(),
                method.endpoint(),
                method.category().name(),
                method.parameters().stream()
                        .map(MethodParameterResponse::from)
                        .toList()
        );
    }

    public record MethodParameterResponse(
            String name,
            String displayName,
            String description,
            String type,
            boolean required,
            Object defaultValue
    ) {
        public static MethodParameterResponse from(ConnectorMethodParameter param) {
            return new MethodParameterResponse(
                    param.name(),
                    param.displayName(),
                    param.description(),
                    param.type(),
                    param.required(),
                    param.defaultValue()
            );
        }
    }
}
