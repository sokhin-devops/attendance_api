package com.attendance.api.dto.attendance;

import com.attendance.api.domain.AttendanceRecord;
import com.attendance.api.domain.Location;
import com.attendance.api.domain.User;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record AttendanceResponse(
        UUID id,
        UUID userId,
        String userName,
        UUID locationId,
        String locationName,
        LocalDate workDate,
        Instant checkInTime,
        Instant checkOutTime,
        Double workedHours,
        boolean late,
        boolean manualOverride,
        String overrideReason,
        String overriddenByName,
        Double gpsAccuracyMeters
) {
    public static AttendanceResponse from(AttendanceRecord a) {
        Instant checkOut = a.getCheckOutTime();
        Double worked = null;
        if (checkOut != null) {
            long minutes = Duration.between(a.getCheckInTime(), checkOut).toMinutes();
            worked = Math.round(minutes / 60.0 * 100) / 100.0;
        }

        // Read each nullable association once rather than per field.
        Location location = a.getLocation();
        User overriddenBy = a.getOverriddenBy();

        return new AttendanceResponse(
                a.getId(),
                a.getUser().getId(),
                a.getUser().getFullName(),
                location == null ? null : location.getId(),
                location == null ? null : location.getName(),
                a.getWorkDate(),
                a.getCheckInTime(),
                checkOut,
                worked,
                a.isLate(),
                a.isManualOverride(),
                a.getOverrideReason(),
                overriddenBy == null ? null : overriddenBy.getFullName(),
                a.getGpsAccuracyMeters());
    }
}
