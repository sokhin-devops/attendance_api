package com.attendance.api.controller;

import com.attendance.api.dto.attendance.AttendanceResponse;
import com.attendance.api.dto.attendance.ManualAttendanceRequest;
import com.attendance.api.service.AttendanceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** Attendance operations that act on another employee's record. */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/attendance")
@Tag(name = "Attendance Admin", description = "Manual attendance override")
@RequiredArgsConstructor
public class AttendanceAdminController {

    private final AttendanceService attendanceService;

    @PutMapping("/override")
    // MANAGER is admitted by the annotation only so the service can answer with the
    // actionable "ask your admin" message; AccessControlService still refuses the write.
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ORG_ADMIN', 'MANAGER')")
    @Operation(summary = "Set or amend an employee's attendance for one day",
            description = """
                    **ORG_ADMIN only** — a `MANAGER` receives 403 and must escalate, per the
                    role model.

                    Creates the day's record when none exists, otherwise amends it. The row is
                    always flagged `isManualOverride` with the supplied reason and the acting
                    admin recorded, and the affected employee is notified.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Attendance recorded"),
            @ApiResponse(responseCode = "400", description = "checkOutTime precedes checkInTime"),
            @ApiResponse(responseCode = "403", description = "Caller is not an organization admin"),
            @ApiResponse(responseCode = "404", description = "Employee or location not found in this organization")
    })
    public AttendanceResponse override(@PathVariable UUID organizationId,
                                       @Valid @RequestBody ManualAttendanceRequest request) {
        return attendanceService.manualOverride(organizationId, request);
    }
}
