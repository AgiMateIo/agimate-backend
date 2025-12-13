package ru.agimate.common.rest;

import ru.agimate.common.exception.ExternalServiceException;
import ru.agimate.common.exception.NotFoundException;
import ru.agimate.common.rest.error.*;
import ru.agimate.common.rest.error.CustomResponseStatusException.LoggingLevel;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.validator.internal.engine.path.PathImpl;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;


@Slf4j
@RequiredArgsConstructor
@RestControllerAdvice
public class BaseErrorHandlerControllerAdvice {

    private static final String AUTH_PRIVATE_KEY_HEADER_NAME = "X-Auth-Key";

    @ExceptionHandler({UnauthorizedStatusException.class})
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ErrorResponse unauthorised(UnauthorizedStatusException ex, HttpServletRequest request, HttpServletResponse response) {
        log.warn("Access denied on {} {}: {}", request.getMethod(), request.getRequestURL(), ex.getMessage());
        return new ErrorResponse(ex.getMessage());
    }

    @ExceptionHandler({ForbiddenStatusException.class})
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ErrorResponse forbidden(ForbiddenStatusException ex, HttpServletRequest request, HttpServletResponse response) {
        log.warn("Forbidden on {} {}: cause: {}", request.getMethod(), request.getRequestURL(), ex.getMessage());
        return new ErrorResponse(ex.getMessage());
    }

    @ExceptionHandler(value = {ConflictStatusException.class})
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorResponse conflict(ConflictStatusException ex, HttpServletRequest request) {
        log.info("Conflict on {} {}: cause: {}", request.getMethod(), request.getRequestURL(), ex.getMessage());
        return new ErrorResponse(ex.getMessage());
    }


    @ExceptionHandler(value = {TooManyRequestsStatusException.class})
    @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
    public ErrorResponse tooManyRequests(TooManyRequestsStatusException ex, HttpServletRequest request) {
        log.warn("Too many requests");
        return new ErrorResponse(ex.getMessage());
    }

    @ExceptionHandler({NotFoundStatusException.class, NotFoundException.class})
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse notFound(RuntimeException ex, HttpServletRequest request) {
        log.info("Not found on {} {}: cause: {}", request.getMethod(), request.getRequestURL(), ex.getMessage());
        return new ErrorResponse(ex.getMessage());
    }


    @ExceptionHandler(BadRequestStatusException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse badRequest(BadRequestStatusException ex, HttpServletRequest request) {
        log.info("Bad request on {} {}: {}", request.getMethod(), request.getRequestURL(), ex.getMessage());
        final HashMap<String, String> invalidFields = new HashMap<>();
        if (ex.getField() != null) {
            invalidFields.put("field", ex.getField());
        }
        return new ErrorResponse(ex.getMessage(), invalidFields);
    }


    @ExceptionHandler(value = {ResponseStatusException.class})
    public ResponseEntity<ErrorResponse> responseStatusException(ResponseStatusException ex, HttpServletRequest request, HttpServletResponse response) {
        log.warn("{} on {} {}: cause: {}", ex.getStatusCode(), request.getMethod(), request.getRequestURL(), ex.getMessage());
        var status = HttpStatus.valueOf(ex.getStatusCode().value());
        var message = Objects.requireNonNullElse(ex.getReason(), ex.getMessage());
        return new ResponseEntity<>(new ErrorResponse(message), status);
    }



    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    public ErrorResponse methodNotSupported(HttpRequestMethodNotSupportedException ex, HttpServletRequest request, HttpServletResponse response) {
        log.info("Not allowed on {} {}: cause: {}", request.getMethod(), request.getRequestURL(), ex.getMessage());
        return new ErrorResponse("method not supported");
    }

    @ExceptionHandler({BindException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleMethodArgumentNotValid(BindException ex, HttpServletRequest request, HttpServletResponse response) {
        log.info("Argument not valid on {} {}: {}", request.getMethod(), request.getRequestURL(), ex.getMessage());
        Map<String, String> errors = new HashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            errors.put(error.getField(), error.getDefaultMessage());
        }
        return new ErrorResponse("Bad request", errors);
    }

    @ExceptionHandler({MissingServletRequestPartException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse missingPart(MissingServletRequestPartException ex, HttpServletRequest request, HttpServletResponse response) {
        log.info("Bad request on {} {}: {}", request.getMethod(), request.getRequestURL(), ex.getMessage());
        return new ErrorResponse("Validation error", Map.of(ex.getRequestPartName(), "field required"));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse missingParameter(MissingServletRequestParameterException ex, HttpServletRequest request, HttpServletResponse response) {
        log.info("Bad request on {} {}: {}", request.getMethod(), request.getRequestURL(), ex.getMessage());
        return new ErrorResponse("Validation error", Map.of(ex.getParameterName(), "query param required"));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> missingHeader(MissingRequestHeaderException ex, HttpServletRequest request, HttpServletResponse response) {
        if (AUTH_PRIVATE_KEY_HEADER_NAME.equals(ex.getHeaderName())) {
            log.info("No auth key on {} {}: {}", request.getMethod(), request.getRequestURL(), ex.getMessage());
            return new ResponseEntity<>(new ErrorResponse("No auth key"), HttpStatus.UNAUTHORIZED);
        } else {
            log.info("Bad request on {} {}: {}", request.getMethod(), request.getRequestURL(), ex.getMessage());
            return new ResponseEntity<>(
                    new ErrorResponse("Validation error", Map.of(ex.getHeaderName(), "header required")),
                    HttpStatus.BAD_REQUEST
            );
        }
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse messageNotReadable(HttpMediaTypeNotSupportedException ex, HttpServletRequest request, HttpServletResponse response) {
        log.warn("Bad request on {} {}: {}", request.getMethod(), request.getRequestURL(), ex.getMessage());
        return new ErrorResponse("Bad request: media type not supported");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse messageNotReadable(HttpMessageNotReadableException ex, HttpServletRequest request, HttpServletResponse response) {
        log.warn("Bad request on {} {}: {}", request.getMethod(), request.getRequestURL(), ex.getMessage(), ex);
        return new ErrorResponse("Bad request: message not readable");
    }

    @ExceptionHandler(MultipartException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse missingParameter(MultipartException ex, HttpServletRequest request, HttpServletResponse response) {
        log.info("Bad request on {} {}: {}", request.getMethod(), request.getRequestURL(), ex.getMessage());
        return new ErrorResponse("Bad multipart request");
    }

    @ExceptionHandler({InternalServerErrorStatusException.class, Exception.class})
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ErrorResponse internalError(Exception ex, HttpServletRequest request, HttpServletResponse response) {
        var errorId = UUID.randomUUID().toString();
        var caller = ex.getStackTrace()[0];


        log.error("Error ID {}: Internal server error -- {}: {} {}.{}:{}",
                errorId,
                ex.getClass().getSimpleName(),
                ex.getMessage(),
                caller.getClassName(),
                caller.getMethodName(),
                caller.getLineNumber(),
                ex
        );

        return new ErrorResponse("Internal server error. Please contact support with Error ID: " + errorId);
    }

    @ExceptionHandler(ExternalServiceException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public ErrorResponse serviceUnavailable(ExternalServiceException ex, HttpServletRequest request, HttpServletResponse response) {
        var responseMsg = "External resource error. Please retry your request later.";

        final var exMessage = ex.getMessage();
        if (exMessage != null && !exMessage.isBlank()) {
            responseMsg += exMessage;
        }

        StackTraceElement caller = ex.getStackTrace()[0];
        log.error("{}: {} {}.{}:{}",
                ex.getClass().getSimpleName(),
                ex.getMessage(),
                caller.getClassName(),
                caller.getMethodName(),
                caller.getLineNumber(),
                ex
        );

        return new ErrorResponse(responseMsg);
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse notFound(NoHandlerFoundException ex, HttpServletRequest request, HttpServletResponse response) {
        log.warn("Page '{}' not found. Referrer: {}", ex.getRequestURL(), request.getHeader("Referer"));
        return new ErrorResponse("page '" + ex.getRequestURL() + "' not found");
    }

    public static ResponseEntity<Object> toEntity(
            @NonNull HttpStatus status,
            @NonNull final String message
    ) {
        var error = new ErrorResponse(message);
        return new ResponseEntity<>(error, status);
    }

    @ExceptionHandler({ConstraintViolationException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleConstraintViolation(ConstraintViolationException ex, HttpServletRequest request) {
        log.info("Constraint violation on {} {}: {}", request.getMethod(), request.getRequestURL(), ex.getMessage());
        Map<String, String> errors = new HashMap<>();
        for (ConstraintViolation<?> violation : ex.getConstraintViolations()) {
            String property;
            final var propertyPath = violation.getPropertyPath();
            if (propertyPath instanceof PathImpl) {
                property = ((PathImpl) propertyPath).getLeafNode().getName();
            } else {
                property = propertyPath.toString();
            }
            errors.put(property, violation.getMessage());
        }
        return new ErrorResponse("Bad request", errors);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    @ResponseStatus(HttpStatus.PAYLOAD_TOO_LARGE)
    public ErrorResponse badRequest(MaxUploadSizeExceededException ex, HttpServletRequest request) {
        log.warn("Payload too large on {} {}: {}", request.getMethod(), request.getRequestURL(), ex.getMessage());
        return new ErrorResponse("Uploaded files exceed the allowed size.");
    }

    @ExceptionHandler(CustomResponseStatusException.class)
    public ErrorResponse customResponseStatusException(
            CustomResponseStatusException ex,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        var logMessage = String.format("Error processing request %s %s: %s",
                request.getMethod(),
                request.getRequestURI(),
                ex.toString()
        );

        if (LoggingLevel.INFO.equals(ex.getLoggingLevel())) {
            log.info(logMessage);
        } else if (LoggingLevel.DEBUG.equals(ex.getLoggingLevel())) {
            log.debug(logMessage);
        } else if (LoggingLevel.WARN.equals(ex.getLoggingLevel())) {
            log.warn(logMessage);
        } else if (LoggingLevel.ERROR.equals(ex.getLoggingLevel())) {
            log.error(logMessage);
        }

        response.setStatus(ex.getHttpCode());
        return new ErrorResponse(ex.getMessage());
    }

}