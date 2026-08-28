package com.attendance.api.dto.attendance;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.util.List;

import com.attendance.api.dto.location.LocationResponse;

/** What the mobile home screen needs in a single call. */
@Schema(description = "Today's check-in state plus the locations this user may use")
public record AttendanceStatusResponse(
        LocalDate workDate,
        boolean checkedIn,
        boolean checkedOut,
        AttendanceResponse todayRecord,
        boolean onApprovedLeave,
        boolean manualCheckInAllowed,
        List<LocationResponse> assignedLocations
) {
}
