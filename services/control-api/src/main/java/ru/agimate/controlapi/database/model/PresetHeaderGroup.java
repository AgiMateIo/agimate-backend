package ru.agimate.controlapi.database.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Headers of which the client must send exactly one, e.g. the two Yandex organization headers.
 *
 * @param oneOf names of the alternative headers; at least two
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PresetHeaderGroup(String id, List<String> oneOf, boolean required) {
}
