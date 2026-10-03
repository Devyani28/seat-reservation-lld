package com.seatreservation.exception;

import com.seatreservation.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(NotFoundException ex) {
        ErrorResponse err = new ErrorResponse(404, "Not Found", ex.getMessage());
        err.setRequestId(MDC.get("requestId"));
        return ResponseEntity.status(404).body(err);
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ErrorResponse> handleConflict(ConflictException ex) {
        ErrorResponse err = new ErrorResponse(409, "Conflict", ex.getMessage(), ex.getReason());
        err.setRequestId(MDC.get("requestId"));
        return ResponseEntity.status(409).body(err);
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ErrorResponse> handleForbidden(ForbiddenException ex) {
        ErrorResponse err = new ErrorResponse(403, "Forbidden", ex.getMessage());
        err.setRequestId(MDC.get("requestId"));
        return ResponseEntity.status(403).body(err);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex) {
        ErrorResponse err = new ErrorResponse(403, "Forbidden", "Access denied");
        err.setRequestId(MDC.get("requestId"));
        return ResponseEntity.status(403).body(err);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuth(AuthenticationException ex) {
        ErrorResponse err = new ErrorResponse(401, "Unauthorized", ex.getMessage());
        err.setRequestId(MDC.get("requestId"));
        return ResponseEntity.status(401).body(err);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
            .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
            .collect(Collectors.joining("; "));
        ErrorResponse err = new ErrorResponse(400, "Bad Request", message);
        err.setRequestId(MDC.get("requestId"));
        return ResponseEntity.status(400).body(err);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArg(IllegalArgumentException ex) {
        ErrorResponse err = new ErrorResponse(400, "Bad Request", ex.getMessage());
        err.setRequestId(MDC.get("requestId"));
        return ResponseEntity.status(400).body(err);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {} {}: {}", request.getMethod(), request.getRequestURI(), ex.getMessage(), ex);
        ErrorResponse err = new ErrorResponse(500, "Internal Server Error", "An unexpected error occurred");
        err.setRequestId(MDC.get("requestId"));
        return ResponseEntity.status(500).body(err);
    }
}
