package com.seatreservation.dto;

import com.seatreservation.model.Seat;
import java.util.UUID;

public class SeatResponse {

    private UUID id;
    private String label;
    private String status;

    public static SeatResponse from(Seat seat) {
        SeatResponse r = new SeatResponse();
        r.id = seat.getId();
        r.label = seat.getLabel();
        r.status = seat.getStatus().name().toLowerCase();
        return r;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
