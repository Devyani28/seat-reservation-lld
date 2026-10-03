package com.seatreservation.controller;

import com.seatreservation.dto.CreateShowRequest;
import com.seatreservation.dto.ShowResponse;
import com.seatreservation.service.ShowService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
public class ShowController {

    private final ShowService showService;

    public ShowController(ShowService showService) {
        this.showService = showService;
    }

    @PostMapping("/shows")
    @ResponseStatus(HttpStatus.CREATED)
    public ShowResponse createShow(@Valid @RequestBody CreateShowRequest request) {
        return showService.createShow(request);
    }

    @GetMapping("/shows/{id}")
    public ShowResponse getShow(@PathVariable UUID id) {
        return showService.getShow(id);
    }
}
