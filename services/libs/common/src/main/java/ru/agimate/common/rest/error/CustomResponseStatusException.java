package ru.agimate.common.rest.error;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;


@Accessors(chain = true)
@ToString
@Setter
@Getter
public class CustomResponseStatusException extends BaseHttpStatusException {

    private final int httpCode;
    private LoggingLevel loggingLevel = LoggingLevel.ERROR;

    public CustomResponseStatusException(int httpCode) {
        super("");
        this.httpCode = httpCode;
    }

    public CustomResponseStatusException(int httpCode, String msg) {
        super(msg);
        this.httpCode = httpCode;
    }

    public CustomResponseStatusException(int httpCode, String msg, LoggingLevel loggingLevel) {
        super(msg);
        this.httpCode = httpCode;
        this.loggingLevel = loggingLevel;
    }

    public CustomResponseStatusException(int httpCode, Throwable cause) {
        super("", cause);
        this.httpCode = httpCode;
    }

    public CustomResponseStatusException(int httpCode, String msg, Throwable cause) {
        super(msg, cause);
        this.httpCode = httpCode;
    }

    public enum LoggingLevel {
        WARN, INFO, DEBUG, ERROR, IGNORE
    }


}
