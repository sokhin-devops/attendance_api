package com.attendance.api.exception;

/** Maps to 403 - authenticated, but not permitted for this resource or tenant. */
public class AccessDeniedBusinessException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AccessDeniedBusinessException(String message) {
        super(message);
    }
}
