package ru.agimate.common.rest.error;

public class UnauthorizedStatusException extends BaseHttpStatusException {

    private static final String DEFAULT_MESSAGE = "Unauthorised request";

    public UnauthorizedStatusException(String message) {
        super(message);
    }

    public UnauthorizedStatusException() {
        super(DEFAULT_MESSAGE);
    }
}
