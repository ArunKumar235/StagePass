package org.stagepass.bookingservice.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "booking_items")
@Data
public class BookingItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Booking booking;

    private UUID seatId;

    private UUID sectionId;

    private String rowLabel;

    private int seatNumber;

    private String tier;

    private String sectionName;

    private BigDecimal price;
}

