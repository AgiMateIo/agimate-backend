package ru.agimate.common.rest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ErrorResponse {

    private ErrorDetails error;

    public ErrorResponse(String error) {
        this.error = new ErrorDetails().setMessage(error);
    }

    public ErrorResponse(String error, Map<String, String> invalidFields) {
        this.error = new ErrorDetails()
                .setMessage(error)
                .setDetails(invalidFields);
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Accessors(chain = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ErrorDetails {

        private String message;

        private Map<String, ?> details;
    }

}
