package com.seatreservation.model;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "seats",
       uniqueConstraints = @UniqueConstraint(columnNames = {"show_id", "label"}))
public class Seat {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "show_id", nullable = false)
    private Show show;

    @Column(nullable = false)
    private String label;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SeatStatus status = SeatStatus.AVAILABLE;

    public Seat() {}

    public Seat(Show show, String label) {
        this.show = show;
        this.label = label;
        this.status = SeatStatus.AVAILABLE;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public Show getShow() { return show; }
    public void setShow(Show show) { this.show = show; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public SeatStatus getStatus() { return status; }
    public void setStatus(SeatStatus status) { this.status = status; }
}
