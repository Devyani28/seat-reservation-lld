package com.seatreservation.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public class CreateShowRequest {

    @NotBlank(message = "name is required")
    private String name;

    @NotEmpty(message = "seats list must not be empty")
    private List<String> seats;

    @NotNull(message = "price_paise is required")
    @Min(value = 0, message = "price_paise must be non-negative")
    private Long pricePaise;

    @Min(value = 1, message = "per_user_limit must be at least 1")
    private int perUserLimit = 4;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public List<String> getSeats() { return seats; }
    public void setSeats(List<String> seats) { this.seats = seats; }
    public Long getPricePaise() { return pricePaise; }
    public void setPricePaise(Long pricePaise) { this.pricePaise = pricePaise; }
    public int getPerUserLimit() { return perUserLimit; }
    public void setPerUserLimit(int perUserLimit) { this.perUserLimit = perUserLimit; }
}
