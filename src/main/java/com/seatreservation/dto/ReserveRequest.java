package com.seatreservation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public class ReserveRequest {

    @NotEmpty(message = "seats list must not be empty")
    private List<String> seats;

    @NotBlank(message = "idempotency_key is required")
    private String idempotencyKey;

    public List<String> getSeats() { return seats; }
    public void setSeats(List<String> seats) { this.seats = seats; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
}
