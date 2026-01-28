package ru.agimate.common.rest.error;

public class BaseHttpStatusException extends RuntimeException {
    public BaseHttpStatusException(String message) {
        super(message);
    }

    public BaseHttpStatusException(String message, Throwable cause) {
        super(message, cause);
    }
}
