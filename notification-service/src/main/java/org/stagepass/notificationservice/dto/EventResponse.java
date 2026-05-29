package org.stagepass.notificationservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventResponse {
    private UUID id;
    private String title;
    private String description;
    private LocalDate eventDate;
    private LocalTime doorsOpenTime;
    private String category;
    private String bannerImageURL;
    private VenueResponse venue;
    private UUID organizerID;
}
