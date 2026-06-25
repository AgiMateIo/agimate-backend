package ru.agimate.controlapi.controller.manage.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.controlapi.connectors.core.dto.TriggerSpec;

import java.util.List;

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
    public static TriggerSpecificationResponse from(String name, TriggerSpec spec) {
        return new TriggerSpecificationResponse(name, spec.description(), spec.params());
    }
}
