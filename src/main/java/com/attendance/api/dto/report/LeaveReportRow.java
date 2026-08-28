package com.attendance.api.dto.report;

import com.attendance.api.domain.enums.LeaveType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(description = "Per-employee, per-type leave usage against entitlement")
public record LeaveReportRow(
        UUID userId,
        String employeeName,
        String email,
        LeaveType leaveType,
        int year,
        int totalDays,
        int usedDays,
        int remainingDays,
        int pendingDays
) {
}
