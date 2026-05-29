package org.stagepass.eventservice.dto;

import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDate;

@Builder
public record EventFilterRequest(

        String city,

        String category,

        LocalDate fromDate,

        LocalDate toDate,

        BigDecimal maxPrice,

        String keyword,

        Integer page,

        Integer size,

        String sortBy
) {
    // canonical compact constructor to enforce default values
    public EventFilterRequest {
        // default page to 0 if unspecified or negative
        if (page == null || page < 0) {
            page = 0;
        }
        // default size to 20 when unspecified or non-positive
        if (size == null || size <= 0) {
            size = 20;
        }
    }
    public String cacheKey() {
        return String.format("city:%s|category:%s|fromDate:%s|toDate:%s|maxPrice:%s|keyword:%s|page:%s|size:%s|sortBy:%s",
                city, category, fromDate, toDate, maxPrice, keyword, page, size, sortBy);
    }
}
