package ru.agimate.common.rest.error;

public class ServiceUnavailableStatusException extends BaseHttpStatusException {
    public ServiceUnavailableStatusException(String message) {
        super(message);
    }

    public ServiceUnavailableStatusException(String message, Throwable cause) {
        super(message, cause);
    }
}
