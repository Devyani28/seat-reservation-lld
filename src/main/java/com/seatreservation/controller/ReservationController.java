package com.seatreservation.controller;

import com.seatreservation.dto.ReservationResponse;
import com.seatreservation.dto.ReserveRequest;
import com.seatreservation.service.ReservationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
public class ReservationController {

    private final ReservationService reservationService;

    public ReservationController(ReservationService reservationService) {
        this.reservationService = reservationService;
    }

    @PostMapping("/shows/{id}/reserve")
    @ResponseStatus(HttpStatus.CREATED)
    public ReservationResponse reserve(
            @PathVariable UUID id,
            @Valid @RequestBody ReserveRequest request,
            Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return reservationService.reserve(id, userId, request);
    }

    @PostMapping("/reservations/{id}/cancel")
    public ReservationResponse cancel(
            @PathVariable UUID id,
            Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return reservationService.cancel(id, userId);
    }
}
