package com.attendance.api.exception;

/** Maps to 409 - the request collides with existing state. */
public class ConflictException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ConflictException(String message) {
        super(message);
    }
}
