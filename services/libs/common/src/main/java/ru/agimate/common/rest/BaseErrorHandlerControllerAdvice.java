package ru.agimate.common.rest;

import ru.agimate.common.rest.error.*;
import ru.agimate.common.rest.error.CustomResponseStatusException.LoggingLevel;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.util.Arrays;
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

    /**
     * Something the installation cannot do at all right now — an unconfigured mail relay, a
     * Centrifugo that is not answering. Until this handler existed the exception fell through to the
     * generic 500, and a caller was told to contact support about a state the operator had chosen.
     */
    @ExceptionHandler(value = {ServiceUnavailableStatusException.class})
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public ErrorResponse serviceUnavailable(ServiceUnavailableStatusException ex, HttpServletRequest request) {
        log.warn("Service unavailable on {} {}: {}", request.getMethod(), request.getRequestURL(), ex.getMessage());
        return new ErrorResponse(ex.getMessage());
    }

    @ExceptionHandler({NotFoundStatusException.class})
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse notFound(RuntimeException ex, HttpServletRequest request) {
        log.info("Not found on {} {}: cause: {}", request.getMethod(), request.getRequestURL(), ex.getMessage());
        return new ErrorResponse(ex.getMessage());
    }


    @ExceptionHandler(ValidationErrorStatusException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse validationError(ValidationErrorStatusException ex, HttpServletRequest request) {
        log.info("Validation error on {} {}: {}", request.getMethod(), request.getRequestURL(), ex.getFields());
        return new ErrorResponse("Bad request", ex.getFields());
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

    /** An unparseable path or query parameter (an enum value outside the set, say) — a client error, not a 500. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse typeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        log.info("Bad request on {} {}: parameter '{}' has unparseable value",
                request.getMethod(), request.getRequestURL(), ex.getName());
        Class<?> target = ex.getRequiredType();
        String expected = target != null && target.isEnum()
                ? " Expected one of: " + Arrays.toString(target.getEnumConstants()) + "."
                : "";
        return new ErrorResponse("Bad request: invalid value for '" + ex.getName() + "'." + expected);
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
            errors.put(leafName(violation.getPropertyPath()), violation.getMessage());
        }
        return new ErrorResponse("Bad request", errors);
    }

    /**
     * Last named segment of a violation path — {@code createUser.dto.email} reports as {@code email},
     * which is what the client binds to a form field. Walks the portable {@link Path} iterator rather
     * than Hibernate Validator's {@code PathImpl}: that one is internal and moved in 9.1.
     *
     * @return the full path when no segment carries a name (cross-parameter constraints)
     */
    private static String leafName(Path path) {
        String leaf = null;
        for (Path.Node node : path) {
            if (node.getName() != null) {
                leaf = node.getName();
            }
        }
        return leaf != null ? leaf : path.toString();
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    @ResponseStatus(HttpStatus.CONTENT_TOO_LARGE)
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