package ru.agimate.common.rest;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/**
 * The one way a client-supplied {@code page}/{@code size} becomes a {@link PageRequest}. Clamped, not
 * rejected: {@link PageRequest#of} throws on a negative page or a size below one, which surfaces as a
 * 500, and an uncapped size lets a single request pull a whole log table into memory. The clamped size
 * is visible to the client in {@link PageResponse#size()}.
 */
public final class PageRequests {

    public static final int MAX_SIZE = 100;

    private PageRequests() {
    }

    public static PageRequest of(int page, int size) {
        return of(page, size, Sort.unsorted());
    }

    public static PageRequest of(int page, int size, Sort sort) {
        return PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_SIZE), sort);
    }
}
