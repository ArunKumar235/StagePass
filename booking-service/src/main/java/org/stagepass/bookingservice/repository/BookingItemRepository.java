package org.stagepass.bookingservice.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.stagepass.bookingservice.entity.BookingItem;

import java.util.List;
import java.util.UUID;

@Repository
public interface BookingItemRepository extends JpaRepository<BookingItem, UUID> {

    List<BookingItem> findByBookingId(UUID bookingId);

    @Query("SELECT b.seatId FROM BookingItem b WHERE b.booking = :bookingId")
    List<UUID> findSeatIdsByBookingId(UUID bookingId);
}
