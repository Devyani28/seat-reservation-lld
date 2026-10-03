package com.seatreservation.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.seatreservation.model.Show;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public class ShowResponse {

    private UUID id;
    private String name;

    @JsonProperty("price_paise")
    private long pricePaise;

    @JsonProperty("per_user_limit")
    private int perUserLimit;

    @JsonProperty("total_seats")
    private int totalSeats;

    @JsonProperty("created_at")
    private Instant createdAt;

    private List<SeatResponse> seats;
    private long available;
    private long held;
    private long confirmed;

    public static ShowResponse from(Show show) {
        ShowResponse r = new ShowResponse();
        r.id = show.getId();
        r.name = show.getName();
        r.pricePaise = show.getPricePaise();
        r.perUserLimit = show.getPerUserLimit();
        r.totalSeats = show.getTotalSeats();
        r.createdAt = show.getCreatedAt();
        r.seats = show.getSeats().stream()
                      .map(SeatResponse::from)
                      .collect(Collectors.toList());
        r.available = r.seats.stream().filter(s -> "available".equals(s.getStatus())).count();
        r.held      = r.seats.stream().filter(s -> "held".equals(s.getStatus())).count();
        r.confirmed = r.seats.stream().filter(s -> "confirmed".equals(s.getStatus())).count();
        return r;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public long getPricePaise() { return pricePaise; }
    public void setPricePaise(long pricePaise) { this.pricePaise = pricePaise; }
    public int getPerUserLimit() { return perUserLimit; }
    public void setPerUserLimit(int perUserLimit) { this.perUserLimit = perUserLimit; }
    public int getTotalSeats() { return totalSeats; }
    public void setTotalSeats(int totalSeats) { this.totalSeats = totalSeats; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public List<SeatResponse> getSeats() { return seats; }
    public void setSeats(List<SeatResponse> seats) { this.seats = seats; }
    public long getAvailable() { return available; }
    public void setAvailable(long available) { this.available = available; }
    public long getHeld() { return held; }
    public void setHeld(long held) { this.held = held; }
    public long getConfirmed() { return confirmed; }
    public void setConfirmed(long confirmed) { this.confirmed = confirmed; }
}
