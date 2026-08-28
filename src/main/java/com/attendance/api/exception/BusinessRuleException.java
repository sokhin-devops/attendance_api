package com.attendance.api.exception;

/** Maps to 400 - a well-formed request that violates a domain rule. */
public class BusinessRuleException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public BusinessRuleException(String message) {
        super(message);
    }
}
