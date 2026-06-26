package ru.agimate.controlapi.controller.error;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import ru.agimate.common.rest.BaseErrorHandlerControllerAdvice;

/**
 * Control-api-specific error handling. Inherits the common handlers from
 * {@link BaseErrorHandlerControllerAdvice}; add control-api-only {@code @ExceptionHandler}s here.
 */
@Slf4j
@RestControllerAdvice
public class ControlApiErrorHandler extends BaseErrorHandlerControllerAdvice {
}
