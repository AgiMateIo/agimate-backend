package ru.agimate.common.rest;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Стабильная форма постраничного ответа. Заменяет прямую сериализацию Spring {@code PageImpl}
 * (её структура не гарантирована между версиями Spring Data) — форму владеет приложение.
 * Держит только неизбыточные поля; {@code first}/{@code last}/{@code empty}/{@code numberOfElements}
 * выводятся клиентом из {@code number}/{@code totalPages}/{@code content}.
 */
@Schema(description = "Paged result")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PageResponse<T>(
        @Schema(description = "Items on this page")
        List<T> content,

        @Schema(description = "Current page number (0-based)")
        int number,

        @Schema(description = "Requested page size")
        int size,

        @Schema(description = "Total items across all pages")
        long totalElements,

        @Schema(description = "Total number of pages")
        int totalPages
) {
    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }
}
