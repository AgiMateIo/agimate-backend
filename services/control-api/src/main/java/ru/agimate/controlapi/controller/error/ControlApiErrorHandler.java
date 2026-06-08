package ru.agimate.controlapi.controller.error;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import ru.agimate.common.rest.BaseErrorHandlerControllerAdvice;
import ru.agimate.common.rest.ErrorResponse;

import java.util.Map;

@Slf4j
@RestControllerAdvice
public class ControlApiErrorHandler extends BaseErrorHandlerControllerAdvice {

    @ExceptionHandler(SkillConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorResponse skillConflict(SkillConflictException ex, HttpServletRequest request) {
        log.info("Skill conflict on {} {}: {}", request.getMethod(), request.getRequestURL(), ex.getMessage());
        return new ErrorResponse(ex.getMessage(), Map.of("existingSkillId", ex.getExistingSkillId().toString()));
    }
}
