package org.stagepass.bookingservice.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.stagepass.bookingservice.entity.Booking;
import org.stagepass.bookingservice.entity.BookingStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BookingRepository extends JpaRepository<Booking, UUID> {

    List<Booking> findByUserIdOrderByCreatedAtDesc(UUID userId);

    List<Booking> findByEventId(UUID eventId);

    List<Booking> findByStatusAndExpiresAtBefore(BookingStatus status, LocalDateTime expiresAtBefore);

    Optional<Booking> findByIdAndUserId(UUID bookingId, UUID userId);

    List<Booking> findByEventIdAndStatusIn(UUID eventId, List<BookingStatus> pending);
}
