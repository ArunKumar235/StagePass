package org.stagepass.eventservice.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;
import java.util.List;

@Entity
@Table(name = "events")
@EntityListeners(AuditingEntityListener.class)
@Data
public class Event {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private String title;

    private String description;

    private String category;

    @ManyToOne
    private Venue venue;

    private LocalDate eventDate;

    private LocalTime doorsOpenTime;

    @Enumerated(EnumType.STRING)
    private EventStatus status;

    private UUID organizerId;

    @Column(name = "banner_image_url")
    private String bannerImageURL;

    @OneToMany(mappedBy = "event", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<SeatSection> sections;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    @CreatedBy
    @Column(name = "created_by")
    private UUID userId;

    @LastModifiedBy
    @Column(name = "last_modified_by")
    private UUID lastModifiedByUserId;
}
