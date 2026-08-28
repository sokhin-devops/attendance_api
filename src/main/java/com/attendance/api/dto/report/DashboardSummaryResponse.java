package com.attendance.api.dto.report;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;

@Schema(description = "Headline metrics for the org dashboard")
public record DashboardSummaryResponse(
        LocalDate asOfDate,
        long activeEmployees,
        long checkedInToday,
        long lateToday,
        long onLeaveToday,
        long absentToday,
        double attendanceRateToday,
        long pendingLeaveRequests,
        long openCheckOutsToday
) {
}
