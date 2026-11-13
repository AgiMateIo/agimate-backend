package ru.agimate.controlapi.database.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Overrides of a connector's credential field for one preset: the preset may tighten or rename a
 * field the connector declares, but it does not duplicate the declaration.
 *
 * @param label    display label in the default language; {@code null} — keep the connector's
 * @param labelRu  Russian label; {@code null} — fall back to {@link #label}
 * @param required whether the field is required in this preset; {@code null} — keep the connector's
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PresetFieldOverride(String label, String labelRu, Boolean required) {
}
