package com.migration.platform.common;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * Uniform paged-list envelope for the list endpoints (#127): {@code content} plus the page number,
 * page size and total element count, so UI tables can drive server-side pagination.
 */
public record PageResponse<T>(List<T> content, int page, int size, long total) {

    public static <E, T> PageResponse<T> of(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(), page.getSize(), page.getTotalElements());
    }
}
