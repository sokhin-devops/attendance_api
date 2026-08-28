package com.attendance.api.dto.common;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/** Uniform envelope for every paginated list endpoint. */
@Schema(description = "Paginated result envelope")
public record PageResponse<T>(
        List<T> data,
        long totalElements,
        int totalPages,
        int currentPage,
        int pageSize,
        boolean hasNext
) {
    public static <E, D> PageResponse<D> of(Page<E> page, Function<E, D> mapper) {
        return new PageResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.getNumber(),
                page.getSize(),
                page.hasNext());
    }
}
