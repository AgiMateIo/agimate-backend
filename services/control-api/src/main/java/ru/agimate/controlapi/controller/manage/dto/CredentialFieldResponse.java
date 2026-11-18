package ru.agimate.controlapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.controlapi.connectors.core.dto.CredentialField;

@Schema(description = "One input of the credentials form")
public record CredentialFieldResponse(
        @Schema(description = "Caption of the input", example = "Server URL (Streamable HTTP)")
        String label,

        @Schema(description = "SECRET is the one to mask; JSON holds an arbitrarily shaped value",
                allowableValues = {"TEXT", "SECRET", "URL", "JSON"}, example = "URL")
        CredentialField.Type type,

        @Schema(description = "Whether an empty value is rejected")
        boolean required,

        @Schema(description = "Whether the input is multi-line; layout only, the value is still masked when SECRET")
        boolean multiline
) {
    public static CredentialFieldResponse from(CredentialField field) {
        return new CredentialFieldResponse(field.label(), field.type(), field.required(), field.multiline());
    }
}
