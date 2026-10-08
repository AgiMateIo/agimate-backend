package ru.agimate.common.rest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("PageRequests.of")
class PageRequestsTest {

    @Test
    @DisplayName("passes valid values through and keeps the sort")
    void passesThrough() {
        var sort = Sort.by("name");

        var r = PageRequests.of(3, 20, sort);

        assertEquals(3, r.getPageNumber());
        assertEquals(20, r.getPageSize());
        assertEquals(sort, r.getSort());
    }

    @Test
    @DisplayName("caps the size at MAX_SIZE")
    void capsSize() {
        assertEquals(PageRequests.MAX_SIZE, PageRequests.of(0, 100_000).getPageSize());
    }

    @Test
    @DisplayName("lifts a size below one and a negative page instead of throwing")
    void liftsInvalid() {
        var r = PageRequests.of(-1, 0);

        assertEquals(0, r.getPageNumber());
        assertEquals(1, r.getPageSize());
        assertEquals(1, PageRequests.of(0, -5).getPageSize());
    }
}
