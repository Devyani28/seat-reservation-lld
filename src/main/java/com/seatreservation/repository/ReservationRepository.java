package com.seatreservation.repository;

import com.seatreservation.model.Reservation;
import com.seatreservation.model.ReservationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReservationRepository extends JpaRepository<Reservation, UUID> {

    Optional<Reservation> findByShowIdAndIdempotencyKey(UUID showId, String idempotencyKey);

    /**
     * Count seats currently held by a user for a show (only CONFIRMED reservations).
     * Used for per-user limit enforcement.
     */
    @Query("""
        SELECT COUNT(rs) FROM Reservation r
        JOIN r.seats rs
        WHERE r.show.id = :showId
          AND r.userId = :userId
          AND r.status = :status
        """)
    long countSeatsByUserAndShow(
        @Param("showId") UUID showId,
        @Param("userId") String userId,
        @Param("status") ReservationStatus status
    );
}
