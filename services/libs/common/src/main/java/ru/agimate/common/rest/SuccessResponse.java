package ru.agimate.common.rest;

import lombok.*;

@Setter
@Getter
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class SuccessResponse<T> {

    private T response;

    public static <T> SuccessResponse<T> ok(T body) {
        return new SuccessResponse<>(body);
    }

    public static <T> SuccessResponse<T> empty() {
        return new SuccessResponse<>(null);
    }
}
