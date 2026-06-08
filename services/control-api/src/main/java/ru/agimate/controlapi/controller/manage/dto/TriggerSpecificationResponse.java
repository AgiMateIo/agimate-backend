package ru.agimate.controlapi.controller.manage.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

@Schema(description = "Trigger specification exposed by an integration connector")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TriggerSpecificationResponse(
        @Schema(description = "Trigger name (e.g., telegram.message_received)")
        String name,

        @Schema(description = "Trigger description")
        String description,

        @Schema(description = "Available parameter names delivered by this trigger")
        List<String> params
) {
    @SuppressWarnings("unchecked")
    public static TriggerSpecificationResponse from(String name, Object rawSpec) {
        if (!(rawSpec instanceof Map<?, ?> spec)) {
            return new TriggerSpecificationResponse(name, null, List.of());
        }
        Object description = spec.get("description");
        Object params = spec.get("params");
        return new TriggerSpecificationResponse(
                name,
                description instanceof String s ? s : null,
                params instanceof List<?> list ? (List<String>) list : List.of()
        );
    }
}
