package ru.agimate.common.rest.error;

public class TooManyRequestsStatusException extends BaseHttpStatusException {
    public TooManyRequestsStatusException() {
        super("Too many requests");
    }
}
