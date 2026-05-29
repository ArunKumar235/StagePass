package org.stagepass.notificationservice.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.stagepass.notificationservice.dto.EventResponse;

import java.util.UUID;

@FeignClient(name = "event-service")
public interface EventServiceClient {

    @GetMapping("/events/{eventId}")
    EventResponse getEventById(@PathVariable("eventId") UUID eventId);
}
