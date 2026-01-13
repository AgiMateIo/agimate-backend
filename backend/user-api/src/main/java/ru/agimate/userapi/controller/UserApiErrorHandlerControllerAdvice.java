package ru.agimate.userapi.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import ru.agimate.common.rest.BaseErrorHandlerControllerAdvice;
import ru.agimate.common.rest.ErrorResponse;

/**
 * Extension of the base error handler to include user-api specific error handling,
 * especially for security-related exceptions that may bubble up to the controller layer.
 */
@Slf4j
@RestControllerAdvice
public class UserApiErrorHandlerControllerAdvice extends BaseErrorHandlerControllerAdvice {

    /**
     * Handle authentication exceptions that may bubble up to the controller layer
     */
    @ExceptionHandler({AuthenticationCredentialsNotFoundException.class})
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ErrorResponse handleAuthenticationCredentialsNotFoundException(
            AuthenticationCredentialsNotFoundException ex,
            HttpServletRequest request,
            HttpServletResponse response) {

        log.warn("Authentication failed on {} {}: {}", request.getMethod(), request.getRequestURL(), ex.getMessage());
        return new ErrorResponse("Authentication credentials not found or invalid");
    }

    /**
     * Handle access denied exceptions that may bubble up to the controller layer
     */
    @ExceptionHandler({AccessDeniedException.class})
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ErrorResponse handleAccessDeniedException(
            AccessDeniedException ex,
            HttpServletRequest request,
            HttpServletResponse response) {

        log.warn("Access denied on {} {}: {}", request.getMethod(), request.getRequestURL(), ex.getMessage());
        return new ErrorResponse("Access denied. Insufficient permissions to access this resource.");
    }
}