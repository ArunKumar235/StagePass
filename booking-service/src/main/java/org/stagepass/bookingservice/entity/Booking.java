package org.stagepass.bookingservice.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "bookings")
@Data
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID  id;

    private UUID userId;

    private UUID eventId;

    @Enumerated(EnumType.STRING)
    private BookingStatus status;

    private BigDecimal totalAmount;

    private String paymentId;

    private String paymentMethod;

    @OneToMany(mappedBy = "booking", cascade = CascadeType.ALL, orphanRemoval = true)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private List<BookingItem> items;

    private LocalDateTime expiresAt;

    private LocalDateTime createdAt;

    private LocalDateTime confirmedAt;

    private LocalDateTime cancelledAt;

    private LocalDateTime eventDate; // for cancellation policy

}
