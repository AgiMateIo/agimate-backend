package ru.agimate.controlapi.controller.error;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import ru.agimate.common.rest.BaseErrorHandlerControllerAdvice;
import ru.agimate.common.rest.ErrorResponse;
import ru.agimate.controlapi.storage.FileRejectedException;
import ru.agimate.controlapi.storage.StoredFileNotFoundException;

/**
 * Control-api-specific error handling. Inherits the common handlers from
 * {@link BaseErrorHandlerControllerAdvice}; add control-api-only {@code @ExceptionHandler}s here.
 */
@Slf4j
@RestControllerAdvice
public class ControlApiErrorHandler extends BaseErrorHandlerControllerAdvice {

    @ExceptionHandler(FileRejectedException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse fileRejected(FileRejectedException ex, HttpServletRequest request) {
        log.warn("File rejected on {} {}: {}", request.getMethod(), request.getRequestURL(), ex.getMessage());
        return new ErrorResponse(ex.getMessage());
    }

    @ExceptionHandler(StoredFileNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse storedFileNotFound(StoredFileNotFoundException ex, HttpServletRequest request) {
        log.warn("File not found on {} {}: {}", request.getMethod(), request.getRequestURL(), ex.getMessage());
        return new ErrorResponse(ex.getMessage());
    }
}
