package org.stagepass.eventservice.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

import java.util.List;

/**
 * Generic wrapper for paginated responses that can be serialized to/from JSON (for Redis caching).
 * This replaces Spring's Page<T> as a cacheable object since PageImpl cannot be deserialized.
 *
 * Includes pagination metadata and sort information.
 */
@Builder
public record PagedResponse<T>(
        @JsonProperty List<T> content,
        @JsonProperty int number,
        @JsonProperty int size,
        @JsonProperty long totalElements,
        @JsonProperty int totalPages,
        @JsonProperty boolean first,
        @JsonProperty boolean last,
        @JsonProperty boolean hasNext,
        @JsonProperty boolean hasPrevious,
        @JsonProperty String sortField,
        @JsonProperty String sortDirection
) {
    public static <T> PagedResponse<T> of(org.springframework.data.domain.Page<T> page) {
        String sortField = null;
        String sortDirection = null;

        if (page.getSort().isSorted()) {
            var iterator = page.getSort().iterator();
            if (iterator.hasNext()) {
                var firstOrder = iterator.next();
                sortField = firstOrder.getProperty();
                sortDirection = firstOrder.getDirection().name();
            }
        }

        return PagedResponse.<T>builder()
                .content(page.getContent())
                .number(page.getNumber())
                .size(page.getSize())
                .totalElements(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .first(page.isFirst())
                .last(page.isLast())
                .hasNext(page.hasNext())
                .hasPrevious(page.hasPrevious())
                .sortField(sortField)
                .sortDirection(sortDirection)
                .build();
    }
}

