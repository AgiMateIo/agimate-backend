package ru.agimate.common.rest.error;

import lombok.Getter;

@Getter
public class ForbiddenStatusException extends BaseHttpStatusException {

    private static final String DEFAULT_MESSAGE = "Access denied";

    private String debugMessage;

    public ForbiddenStatusException(String message) {
        super(message);
    }

    public ForbiddenStatusException(String message, Throwable cause) {
        super(message, cause);
    }
}
