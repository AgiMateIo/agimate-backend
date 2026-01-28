package ru.agimate.common.rest.error;

import lombok.Getter;

@Getter
public class BadRequestStatusException extends BaseHttpStatusException {

    private String field;

    public BadRequestStatusException(String message, String field) {
        super(message);
        this.field = field;
    }

    public BadRequestStatusException(String message) {
        super(message);
    }

    public BadRequestStatusException(String message, Throwable cause) {
        super(message, cause);
    }

}
