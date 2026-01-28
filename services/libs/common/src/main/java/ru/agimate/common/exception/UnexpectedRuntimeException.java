package ru.agimate.common.exception;

public class UnexpectedRuntimeException extends ProjectBaseException {

    public UnexpectedRuntimeException(String message) {
        super(message, null);
    }

    public UnexpectedRuntimeException(String message, Throwable cause) {
        super(message, cause);
    }
}
