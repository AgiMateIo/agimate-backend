package ru.agimate.common.exception;

public abstract class ProjectBaseException extends RuntimeException {
    public ProjectBaseException(String message) {
        super(message);
    }

    public ProjectBaseException(String message, Throwable cause) {
        super(message, cause);
    }
}
