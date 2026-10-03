package com.seatreservation.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.seatreservation.model.Reservation;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public class ReservationResponse {

    @JsonProperty("reservation_id")
    private UUID reservationId;

    @JsonProperty("show_id")
    private UUID showId;

    @JsonProperty("user_id")
    private String userId;

    private List<String> seats;

    @JsonProperty("amount_paise")
    private long amountPaise;

    private String status;

    @JsonProperty("created_at")
    private Instant createdAt;

    public static ReservationResponse from(Reservation reservation) {
        ReservationResponse r = new ReservationResponse();
        r.reservationId = reservation.getId();
        r.showId = reservation.getShow().getId();
        r.userId = reservation.getUserId();
        r.seats = reservation.getSeats().stream()
                             .map(s -> s.getLabel())
                             .sorted()
                             .collect(Collectors.toList());
        r.amountPaise = reservation.getAmountPaise();
        r.status = reservation.getStatus().name().toLowerCase();
        r.createdAt = reservation.getCreatedAt();
        return r;
    }

    public UUID getReservationId() { return reservationId; }
    public void setReservationId(UUID reservationId) { this.reservationId = reservationId; }
    public UUID getShowId() { return showId; }
    public void setShowId(UUID showId) { this.showId = showId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public List<String> getSeats() { return seats; }
    public void setSeats(List<String> seats) { this.seats = seats; }
    public long getAmountPaise() { return amountPaise; }
    public void setAmountPaise(long amountPaise) { this.amountPaise = amountPaise; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
