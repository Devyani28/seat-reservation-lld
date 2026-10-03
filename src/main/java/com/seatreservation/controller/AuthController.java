package com.seatreservation.controller;

import com.seatreservation.config.JwtService;
import com.seatreservation.dto.TokenRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final JwtService jwtService;

    public AuthController(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    /**
     * Issues a JWT for the given user_id and role.
     * In production this would validate credentials; here it's a convenience endpoint
     * for testing and the burst script.
     */
    @PostMapping("/token")
    public Map<String, String> token(@Valid @RequestBody TokenRequest request) {
        String token = jwtService.generateToken(request.getUserId(), request.getRole());
        return Map.of(
            "token", token,
            "user_id", request.getUserId(),
            "role", request.getRole()
        );
    }
}
