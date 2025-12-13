package ru.agimate.common.rest.error;

public class ConflictStatusException extends BaseHttpStatusException {
    public ConflictStatusException(String message) {
        super(message);
    }
}
