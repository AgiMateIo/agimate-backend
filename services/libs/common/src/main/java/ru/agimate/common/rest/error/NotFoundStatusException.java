package ru.agimate.common.rest.error;

public class NotFoundStatusException extends BaseHttpStatusException {

    public NotFoundStatusException(final String objectType, String searchTerm) {
        super("Can not find '" + objectType + "' for '" + searchTerm + "'");
    }

    public NotFoundStatusException(String message) {
        super(message);
    }

}
