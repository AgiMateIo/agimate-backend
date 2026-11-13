package ru.agimate.controlapi.database.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One extra HTTP header the connection form asks for. Not discoverable from a 401: a vendor doc or a
 * probe finding states it.
 *
 * @param valuePrefix prefixed by the client to the entered value ("Bearer ", "OAuth "); empty — none
 * @param keyPrefix   part of the credential the user enters ("op_", "krsp_"); the client must not
 *                    prepend it, only show it as a hint
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PresetHeaderField(
        String name,
        String label,
        String labelRu,
        boolean secret,
        boolean required,
        String valuePrefix,
        String keyPrefix
) {
}
