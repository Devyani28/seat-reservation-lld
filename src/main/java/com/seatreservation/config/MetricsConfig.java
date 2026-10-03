package com.seatreservation.config;

import com.seatreservation.model.SeatStatus;
import com.seatreservation.repository.SeatRepository;
import com.seatreservation.repository.ShowRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Configuration
public class MetricsConfig {

    private final MeterRegistry meterRegistry;
    private final ShowRepository showRepository;
    private final SeatRepository seatRepository;

    // Cache of show_id → available seat count (updated by scheduler)
    private final ConcurrentHashMap<String, AtomicLong> availableSeatsCache = new ConcurrentHashMap<>();

    public MetricsConfig(MeterRegistry meterRegistry,
                         ShowRepository showRepository,
                         SeatRepository seatRepository) {
        this.meterRegistry = meterRegistry;
        this.showRepository = showRepository;
        this.seatRepository = seatRepository;
    }

    /**
     * Refresh seats_available gauge every 5 seconds.
     * This keeps the gauge reconciled with actual DB state.
     */
    @Scheduled(fixedDelay = 5000)
    public void refreshAvailableSeatsGauge() {
        try {
            showRepository.findAll().forEach(show -> {
                String showId = show.getId().toString();
                long count = seatRepository.countByShowIdAndStatus(show.getId(), SeatStatus.AVAILABLE);

                availableSeatsCache.computeIfAbsent(showId, id -> {
                    AtomicLong gauge = new AtomicLong(count);
                    Gauge.builder("seats_available", gauge, AtomicLong::get)
                         .tag("show_id", id)
                         .description("Number of available seats for a show")
                         .register(meterRegistry);
                    return gauge;
                }).set(count);
            });
        } catch (Exception e) {
            // Don't let metrics refresh crash the app
        }
    }
}
