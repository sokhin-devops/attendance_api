package com.attendance.api.dto.report;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(description = "Per-employee lateness summary")
public record LatenessReportRow(
        UUID userId,
        String employeeName,
        String email,
        int checkInsRecorded,
        int lateCheckIns,
        double latenessRate,
        Double averageMinutesLate
) {
}
