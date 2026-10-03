package com.seatreservation.service;

import com.seatreservation.dto.ReservationResponse;
import com.seatreservation.dto.ReserveRequest;
import com.seatreservation.exception.ConflictException;
import com.seatreservation.exception.ForbiddenException;
import com.seatreservation.exception.NotFoundException;
import com.seatreservation.model.*;
import com.seatreservation.repository.ReservationRepository;
import com.seatreservation.repository.SeatRepository;
import com.seatreservation.repository.ShowRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class ReservationService {

    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);

    private final ShowRepository showRepository;
    private final SeatRepository seatRepository;
    private final ReservationRepository reservationRepository;
    private final MeterRegistry meterRegistry;

    public ReservationService(ShowRepository showRepository,
                              SeatRepository seatRepository,
                              ReservationRepository reservationRepository,
                              MeterRegistry meterRegistry) {
        this.showRepository = showRepository;
        this.seatRepository = seatRepository;
        this.reservationRepository = reservationRepository;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Reserve seats atomically.
     *
     * Correctness mechanism:
     * 1. SELECT ... FOR UPDATE on seat rows in deterministic label order → exclusive row locks,
     *    no read-then-write gap, no deadlock between concurrent multi-seat requests.
     * 2. All checks (seat status, per-user limit) happen inside the lock.
     * 3. UNIQUE (show_id, idempotency_key) constraint is the final backstop for exactly-once.
     *
     * Isolation: READ_COMMITTED is sufficient because we hold explicit row locks (FOR UPDATE).
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ReservationResponse reserve(UUID showId, String userId, ReserveRequest request) {
        MDC.put("showId", showId.toString());

        String idempotencyKey = request.getIdempotencyKey();
        List<String> requestedLabels = request.getSeats().stream().distinct().toList();

        // ── 1. Idempotency check (fast path before locking) ──────────────────────
        Optional<Reservation> existing =
            reservationRepository.findByShowIdAndIdempotencyKey(showId, idempotencyKey);

        if (existing.isPresent()) {
            Reservation res = existing.get();
            // Same key, same user → replay
            if (res.getUserId().equals(userId)) {
                List<String> existingLabels = res.getSeats().stream()
                    .map(Seat::getLabel).sorted().toList();
                List<String> sortedRequested = requestedLabels.stream().sorted().toList();

                if (!existingLabels.equals(sortedRequested)) {
                    // Same key, different seats → reject
                    incrementDeclined(showId, "key_conflict");
                    throw new ConflictException(
                        "Idempotency key already used with different seats", "key_conflict");
                }
                // Exact replay
                incrementDeclined(showId, "idempotent_replay");
                log.info("Idempotent replay reservation={} user={}", res.getId(), userId);
                return ReservationResponse.from(res);
            } else {
                // Different user, same key on same show → reject
                incrementDeclined(showId, "key_conflict");
                throw new ConflictException(
                    "Idempotency key already used by another user", "key_conflict");
            }
        }

        // ── 2. Load show ──────────────────────────────────────────────────────────
        Show show = showRepository.findById(showId)
            .orElseThrow(() -> new NotFoundException("Show not found: " + showId));

        // ── 3. Acquire row locks in deterministic label order (prevents deadlock) ─
        List<Seat> lockedSeats = seatRepository.findAndLockByShowIdAndLabels(
            showId, requestedLabels);

        // Verify all requested seats exist
        if (lockedSeats.size() != requestedLabels.size()) {
            Set<String> foundLabels = lockedSeats.stream()
                .map(Seat::getLabel).collect(Collectors.toSet());
            List<String> missing = requestedLabels.stream()
                .filter(l -> !foundLabels.contains(l))
                .toList();
            throw new NotFoundException("Seats not found in this show: " + missing);
        }

        // ── 4. Check all seats are AVAILABLE (all-or-nothing) ────────────────────
        List<String> unavailable = lockedSeats.stream()
            .filter(s -> s.getStatus() != SeatStatus.AVAILABLE)
            .map(Seat::getLabel)
            .toList();

        if (!unavailable.isEmpty()) {
            incrementDeclined(showId, "seat_taken");
            log.info("Seat(s) unavailable={} user={} show={}", unavailable, userId, showId);
            throw new ConflictException(
                "Seat(s) already taken: " + unavailable, "seat_taken");
        }

        // ── 5. Per-user limit check ───────────────────────────────────────────────
        long alreadyHeld = reservationRepository.countSeatsByUserAndShow(
            showId, userId, ReservationStatus.CONFIRMED);
        int limit = show.getPerUserLimit();

        if (alreadyHeld + requestedLabels.size() > limit) {
            incrementDeclined(showId, "per_user_limit");
            log.info("Per-user limit exceeded user={} show={} held={} requested={} limit={}",
                userId, showId, alreadyHeld, requestedLabels.size(), limit);
            throw new ConflictException(
                "Per-user seat limit (" + limit + ") would be exceeded", "per_user_limit");
        }

        // ── 6. Mark seats CONFIRMED ───────────────────────────────────────────────
        for (Seat seat : lockedSeats) {
            seat.setStatus(SeatStatus.CONFIRMED);
        }
        seatRepository.saveAll(lockedSeats);

        // ── 7. Create reservation ─────────────────────────────────────────────────
        long amountPaise = show.getPricePaise() * requestedLabels.size();
        Reservation reservation = new Reservation(show, userId, idempotencyKey, amountPaise);
        reservation.setSeats(lockedSeats);

        try {
            reservation = reservationRepository.save(reservation);
        } catch (DataIntegrityViolationException e) {
            // Race: another transaction committed the same idempotency key just now.
            // Re-read and replay.
            Optional<Reservation> raceExisting =
                reservationRepository.findByShowIdAndIdempotencyKey(showId, idempotencyKey);
            if (raceExisting.isPresent()) {
                incrementDeclined(showId, "idempotent_replay");
                return ReservationResponse.from(raceExisting.get());
            }
            // Unexpected constraint violation — rethrow
            throw e;
        }

        incrementConfirmed(showId);
        log.info("Reservation confirmed id={} user={} show={} seats={} amount={}",
            reservation.getId(), userId, showId, requestedLabels, amountPaise);

        return ReservationResponse.from(reservation);
    }

    /**
     * Cancel a reservation. Only the owner may cancel.
     * Seats are returned to AVAILABLE atomically.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ReservationResponse cancel(UUID reservationId, String userId) {
        Reservation reservation = reservationRepository.findById(reservationId)
            .orElseThrow(() -> new NotFoundException("Reservation not found: " + reservationId));

        if (!reservation.getUserId().equals(userId)) {
            throw new ForbiddenException("You can only cancel your own reservations");
        }

        if (reservation.getStatus() == ReservationStatus.CANCELLED) {
            // Already cancelled — idempotent
            return ReservationResponse.from(reservation);
        }

        reservation.setStatus(ReservationStatus.CANCELLED);

        // Return seats to AVAILABLE — but only if they are still CONFIRMED
        // (guards against a seat being re-sold and then this cancel running)
        for (Seat seat : reservation.getSeats()) {
            if (seat.getStatus() == SeatStatus.CONFIRMED) {
                seat.setStatus(SeatStatus.AVAILABLE);
            }
        }
        seatRepository.saveAll(reservation.getSeats());
        reservationRepository.save(reservation);

        log.info("Reservation cancelled id={} user={}", reservationId, userId);
        return ReservationResponse.from(reservation);
    }

    // ── Metrics helpers ───────────────────────────────────────────────────────

    private void incrementConfirmed(UUID showId) {
        meterRegistry.counter("reservations_confirmed_total",
            "show_id", showId.toString()).increment();
    }

    private void incrementDeclined(UUID showId, String reason) {
        meterRegistry.counter("reservations_declined_total",
            "show_id", showId.toString(),
            "reason", reason).increment();
    }
}
