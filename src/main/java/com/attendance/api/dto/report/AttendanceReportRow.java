package com.attendance.api.dto.report;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/** One employee's attendance breakdown over the reported window. */
@Schema(description = "Per-employee attendance summary")
public record AttendanceReportRow(
        UUID userId,
        String employeeName,
        String email,
        int workingDays,
        int presentDays,
        int lateDays,
        int leaveDays,
        int absentDays,
        double attendanceRate,
        double latenessRate,
        double absenteeismRate,
        double totalHoursWorked
) {
}
