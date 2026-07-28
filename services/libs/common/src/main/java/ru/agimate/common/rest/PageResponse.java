package ru.agimate.common.rest;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.Page;

import java.util.List;

/**
 * A stable shape for a paginated response. It replaces serialising Spring's {@code PageImpl} directly
 * (whose structure is not guaranteed across Spring Data versions) — the application owns the shape. It
 * keeps only the non-redundant fields; {@code first}/{@code last}/{@code empty}/{@code numberOfElements}
 * are derived by the client from {@code number}/{@code totalPages}/{@code content}.
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
