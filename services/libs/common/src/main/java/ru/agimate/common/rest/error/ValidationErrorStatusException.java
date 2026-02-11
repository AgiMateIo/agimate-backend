package ru.agimate.common.rest.error;

import lombok.Getter;

import java.util.Map;

@Getter
public class ValidationErrorStatusException extends BaseHttpStatusException {

    private final Map<String, String> fields;

    public ValidationErrorStatusException(Map<String, String> fields) {
        super("Bad request");
        this.fields = fields;
    }

    public ValidationErrorStatusException(String field, String message) {
        super("Bad request");
        this.fields = Map.of(field, message);
    }
}
