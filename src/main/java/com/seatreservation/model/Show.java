package com.seatreservation.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "shows")
public class Show {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(name = "price_paise", nullable = false)
    private long pricePaise;

    @Column(name = "per_user_limit", nullable = false)
    private int perUserLimit = 4;

    @Column(name = "total_seats", nullable = false)
    private int totalSeats = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @OneToMany(mappedBy = "show", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @OrderBy("label ASC")
    private List<Seat> seats = new ArrayList<>();

    public Show() {}

    public Show(String name, long pricePaise, int perUserLimit) {
        this.name = name;
        this.pricePaise = pricePaise;
        this.perUserLimit = perUserLimit;
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
    public List<Seat> getSeats() { return seats; }
    public void setSeats(List<Seat> seats) { this.seats = seats; }
}
