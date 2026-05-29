package org.stagepass.eventservice.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.util.UUID;
import java.util.List;

@Entity
@Table(name = "seat_sections")
@Data
public class SeatSection {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne
    private Event event;

    private String sectionName;

    @Enumerated(EnumType.STRING)
    private SeatTier tier;

    private int rowCount;

    private int seatsPerRow;

    @OneToMany(mappedBy = "section", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Seat> seats;

}
