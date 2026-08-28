package com.attendance.api.exception;

import com.attendance.api.dto.common.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;

import jakarta.validation.ConstraintViolationException;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.lang.NonNull;

/** Single place that turns exceptions into the {@link ApiErrorResponse} contract. */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException ex,
                                                             HttpServletRequest request) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.putIfAbsent(fe.getField(), fe.getDefaultMessage());
        }
        ex.getBindingResult().getGlobalErrors()
                .forEach(ge -> fieldErrors.putIfAbsent(ge.getObjectName(), ge.getDefaultMessage()));

        return ResponseEntity.badRequest().body(ApiErrorResponse.validation(
                "Request validation failed", request.getRequestURI(), fieldErrors));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleConstraintViolation(ConstraintViolationException ex,
                                                                     HttpServletRequest request) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        ex.getConstraintViolations().forEach(v ->
                fieldErrors.putIfAbsent(v.getPropertyPath().toString(), v.getMessage()));

        return ResponseEntity.badRequest().body(ApiErrorResponse.validation(
                "Request validation failed", request.getRequestURI(), fieldErrors));
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNotFound(ResourceNotFoundException ex,
                                                           HttpServletRequest request) {
        return status(HttpStatus.NOT_FOUND, ex.getMessage(), request);
    }

    @ExceptionHandler(BusinessRuleException.class)
    public ResponseEntity<ApiErrorResponse> handleBusinessRule(BusinessRuleException ex,
                                                               HttpServletRequest request) {
        return status(HttpStatus.BAD_REQUEST, ex.getMessage(), request);
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiErrorResponse> handleConflict(ConflictException ex,
                                                           HttpServletRequest request) {
        return status(HttpStatus.CONFLICT, ex.getMessage(), request);
    }

    @ExceptionHandler(GeofenceViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleGeofence(GeofenceViolationException ex,
                                                           HttpServletRequest request) {
        Map<String, String> details = Map.of(
                "distanceMeters", String.format("%.1f", ex.getDistanceMeters()),
                "allowedRadiusMeters", String.valueOf(ex.getAllowedRadiusMeters()));
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ApiErrorResponse(
                java.time.Instant.now(), HttpStatus.FORBIDDEN.value(), "Outside Geofence",
                ex.getMessage(), request.getRequestURI(), details));
    }

    @ExceptionHandler({AccessDeniedBusinessException.class, AccessDeniedException.class})
    public ResponseEntity<ApiErrorResponse> handleForbidden(Exception ex, HttpServletRequest request) {
        String message = ex instanceof AccessDeniedBusinessException
                ? ex.getMessage()
                : "You do not have permission to perform this action";
        return status(HttpStatus.FORBIDDEN, message, request);
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiErrorResponse> handleBadCredentials(BadCredentialsException ex,
                                                                 HttpServletRequest request) {
        // Never disclose whether the email or the password was the wrong half; the real
        // reason goes to the log so operators can still diagnose it.
        log.debug("Authentication failed on {}: {}", request.getRequestURI(), ex.getMessage());
        return status(HttpStatus.UNAUTHORIZED, "Invalid email or password", request);
    }

    @ExceptionHandler(DisabledException.class)
    public ResponseEntity<ApiErrorResponse> handleDisabled(DisabledException ex,
                                                           HttpServletRequest request) {
        log.debug("Rejected a deactivated principal on {}: {}",
                request.getRequestURI(), ex.getMessage());
        return status(HttpStatus.FORBIDDEN, "This account has been deactivated", request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleDataIntegrity(DataIntegrityViolationException ex,
                                                                HttpServletRequest request) {
        log.warn("Data integrity violation on {}: {}", request.getRequestURI(),
                ex.getMostSpecificCause().getMessage());
        return status(HttpStatus.CONFLICT,
                "The request conflicts with existing data or a database constraint", request);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class,
                       MethodArgumentTypeMismatchException.class,
                       MissingServletRequestParameterException.class,
                       IllegalArgumentException.class})
    public ResponseEntity<ApiErrorResponse> handleMalformed(Exception ex, HttpServletRequest request) {
        return status(HttpStatus.BAD_REQUEST,
                ex instanceof IllegalArgumentException ? ex.getMessage() : "Malformed request", request);
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNoHandler(NoHandlerFoundException ex,
                                                            HttpServletRequest request) {
        return status(HttpStatus.NOT_FOUND, "No endpoint " + ex.getHttpMethod() + " " + ex.getRequestURL(), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return status(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred", request);
    }

    private ResponseEntity<ApiErrorResponse> status(@NonNull HttpStatus httpStatus, String message,
                                                    HttpServletRequest request) {
        return ResponseEntity.status(httpStatus).body(ApiErrorResponse.of(
                httpStatus.value(), httpStatus.getReasonPhrase(), message, request.getRequestURI()));
    }
}
