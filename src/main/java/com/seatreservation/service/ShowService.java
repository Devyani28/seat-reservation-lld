package com.seatreservation.service;

import com.seatreservation.dto.CreateShowRequest;
import com.seatreservation.dto.ShowResponse;
import com.seatreservation.model.Seat;
import com.seatreservation.model.Show;
import com.seatreservation.repository.SeatRepository;
import com.seatreservation.repository.ShowRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class ShowService {

    private static final Logger log = LoggerFactory.getLogger(ShowService.class);

    private final ShowRepository showRepository;
    private final SeatRepository seatRepository;

    public ShowService(ShowRepository showRepository, SeatRepository seatRepository) {
        this.showRepository = showRepository;
        this.seatRepository = seatRepository;
    }

    @Transactional
    public ShowResponse createShow(CreateShowRequest request) {
        // Deduplicate seat labels
        List<String> labels = request.getSeats().stream().distinct().toList();

        Show show = new Show(request.getName(), request.getPricePaise(), request.getPerUserLimit());
        show.setTotalSeats(labels.size());
        show = showRepository.save(show);

        List<Seat> seats = new ArrayList<>(labels.size());
        for (String label : labels) {
            seats.add(new Seat(show, label));
        }
        seatRepository.saveAll(seats);
        show.setSeats(seats);

        log.info("Created show id={} name={} seats={}", show.getId(), show.getName(), labels.size());
        return ShowResponse.from(show);
    }

    @Transactional(readOnly = true)
    public ShowResponse getShow(UUID showId) {
        Show show = showRepository.findById(showId)
            .orElseThrow(() -> new com.seatreservation.exception.NotFoundException("Show not found: " + showId));

        // Load seats explicitly (lazy)
        List<Seat> seats = seatRepository.findByShowIdOrderByLabelAsc(showId);
        show.setSeats(seats);

        return ShowResponse.from(show);
    }
}
