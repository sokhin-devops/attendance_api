package com.attendance.api.domain.enums;

/** Derived per-day attendance outcome used by reports; never persisted. */
public enum DayStatus {
    PRESENT,
    LATE,
    ON_LEAVE,
    ABSENT
}
