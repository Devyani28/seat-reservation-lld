package com.seatreservation.repository;

import com.seatreservation.model.Seat;
import com.seatreservation.model.SeatStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface SeatRepository extends JpaRepository<Seat, UUID> {

    List<Seat> findByShowIdOrderByLabelAsc(UUID showId);

    /**
     * Lock seat rows in deterministic label order to prevent deadlocks.
     * FOR UPDATE ensures exclusive row-level lock within the transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT s FROM Seat s
        WHERE s.show.id = :showId AND s.label IN :labels
        ORDER BY s.label ASC
        """)
    List<Seat> findAndLockByShowIdAndLabels(
        @Param("showId") UUID showId,
        @Param("labels") List<String> labels
    );

    @Query("""
        SELECT COUNT(s) FROM Seat s
        WHERE s.show.id = :showId AND s.status = :status
        """)
    long countByShowIdAndStatus(@Param("showId") UUID showId, @Param("status") SeatStatus status);
}
