package ru.agimate.common.rest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("PageResponse.from")
class PageResponseTest {

    @Test
    @DisplayName("переносит неизбыточные поля Page (без pageable/sort и без выводимых first/last/empty)")
    void mapsFields() {
        var page = new PageImpl<>(List.of("a", "b"), PageRequest.of(1, 5), 12);

        PageResponse<String> r = PageResponse.from(page);

        assertEquals(List.of("a", "b"), r.content());
        assertEquals(1, r.number());
        assertEquals(5, r.size());
        assertEquals(12, r.totalElements());
        assertEquals(3, r.totalPages()); // ceil(12 / 5)
    }

    @Test
    @DisplayName("пустая страница → пустой content, totalElements=0")
    void emptyPage() {
        PageResponse<String> r = PageResponse.from(
                new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        assertEquals(List.of(), r.content());
        assertEquals(0, r.totalElements());
        assertEquals(0, r.totalPages());
    }
}
