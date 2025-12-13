package ru.agimate.common.rest.error;

public class InternalServerErrorStatusException extends RuntimeException {
    public InternalServerErrorStatusException(String message, Throwable cause) {
        super(message, cause);
    }
}
