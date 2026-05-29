package org.stagepass.eventservice.dto;

import jakarta.validation.constraints.*;
import lombok.Builder;
import org.stagepass.eventservice.entity.SeatTier;

import java.math.BigDecimal;

@Builder
public record SeatTierPricing (

        @NotBlank(message = "Section name must not be blank")
        @Size(min = 2, max = 100, message = "Section name must be between 2 and 100 characters")
        String sectionName,

        @NotNull(message = "Seat tier must not be null")
        SeatTier tier,

        @NotNull(message = "Price must not be null")
        @DecimalMin(value = "0.00", message = "Price must be zero or positive")
        @Digits(integer = 8, fraction = 2,
                message = "Price must have at most 8 integer digits and 2 decimal places")
        BigDecimal price,

        @NotNull(message = "Row count must not be null")
        @Min(value = 1,   message = "Row count must be at least 1")
        @Max(value = 100, message = "Row count must not exceed 100")
        Integer rowCount,

        @NotNull(message = "Seats per row must not be null")
        @Min(value = 1,   message = "Seats per row must be at least 1")
        @Max(value = 200, message = "Seats per row must not exceed 200")
        Integer seatsPerRow
)
{
        public int totalSeats() {
                if (rowCount == null || seatsPerRow == null) return 0;
                return rowCount * seatsPerRow;
        }
}
