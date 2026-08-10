package ru.agimate.controlapi.service.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * An app's trigger for the manage UI. {@code paramsSchema} is the raw JSON Schema of the event's
 * payload ({@code null} when the app did not send it); {@code params} is a derived list of names for
 * simple display.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AppTrigger(
        String name,
        String title,
        String description,
        List<String> params,
        Object paramsSchema
) {
}
