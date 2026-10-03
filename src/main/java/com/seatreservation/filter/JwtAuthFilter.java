package com.seatreservation.filter;

import com.seatreservation.config.JwtService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    // Logs to com.seatreservation.* → goes to sr-api.log
    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);

    private final JwtService jwtService;

    public JwtAuthFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        long startTs = System.currentTimeMillis();

        // Assign a correlation/request ID
        String requestId = UUID.randomUUID().toString().substring(0, 8);
        MDC.put("requestId", requestId);
        response.setHeader("X-Request-Id", requestId);

        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            try {
                Claims claims = jwtService.parseToken(token);
                String userId = claims.getSubject();
                String role   = claims.get("role", String.class);

                MDC.put("userId", userId);

                var auth = new UsernamePasswordAuthenticationToken(
                    userId,
                    null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + (role != null ? role : "USER")))
                );
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (JwtException e) {
                // Invalid token — security config will return 401
            }
        }

        // ── Incoming request log (sr-api.log) ─────────────────────────────
        log.info("incoming method={} uri={} requestId={}",
            request.getMethod(),
            request.getRequestURI(),
            requestId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            long durationMs = System.currentTimeMillis() - startTs;
            // ── Outgoing response log (sr-api.log) ────────────────────────
            log.info("completed method={} uri={} status={} durationMs={} requestId={}",
                request.getMethod(),
                request.getRequestURI(),
                response.getStatus(),
                durationMs,
                requestId);
            MDC.clear();
        }
    }
}
