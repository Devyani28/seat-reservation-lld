package com.seatreservation.dto;

import jakarta.validation.constraints.NotBlank;

public class TokenRequest {

    @NotBlank(message = "user_id is required")
    private String userId;

    private String role = "USER";

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
}
