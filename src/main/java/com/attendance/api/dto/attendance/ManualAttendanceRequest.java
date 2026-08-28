package com.attendance.api.dto.attendance;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Org-admin override. Creates the day's record when none exists, otherwise
 * amends the existing one. Always flags the row as a manual override.
 */
@Schema(description = "Admin override of an employee's attendance for one day")
public record ManualAttendanceRequest(
        @NotNull
        @Schema(description = "Employee whose attendance is being set")
        UUID userId,

        @NotNull
        @Schema(example = "2026-08-27", description = "Calendar day being adjusted")
        LocalDate workDate,

        @NotNull
        @Schema(description = "Check-in instant to record")
        Instant checkInTime,

        @Schema(description = "Check-out instant; omit to leave the day open")
        Instant checkOutTime,

        @Schema(description = "Location to attribute the day to")
        UUID locationId,

        @NotBlank @Size(max = 500)
        @Schema(example = "Forgot to check in; verified with supervisor")
        String reason
) {
}
