package org.stagepass.eventservice.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "venues")
@Data
public class Venue {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private String name;

    private String address;

    private String city;

    private String state;

    private String country;

    private int totalCapacity;

    private String mapImageUrl;

    @OneToMany(mappedBy = "venue", fetch = FetchType.LAZY)
    private List<Event> events;

}
