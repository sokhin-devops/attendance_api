package com.attendance.api.controller;

import com.attendance.api.dto.report.*;
import com.attendance.api.service.ReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/organizations/{organizationId}")
@Tag(name = "Reports", description = "Attendance analytics, dashboards and CSV export")
@RequiredArgsConstructor
public class ReportController {

    private static final String CSV = "text/csv";

    private final ReportService reportService;

    @GetMapping("/dashboard/summary")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ORG_ADMIN', 'MANAGER', 'EMPLOYEE')")
    @Operation(summary = "Headline metrics for today",
            description = """
                    Scope follows the caller: **ORG_ADMIN** gets the whole organization,
                    **MANAGER** their own team, **EMPLOYEE** just themselves. "Today" is
                    resolved in the organization's timezone, and weekends report a 0%
                    attendance rate rather than a false shortfall.
                    """)
    public DashboardSummaryResponse dashboard(@PathVariable UUID organizationId) {
        return reportService.dashboardSummary(organizationId);
    }

    @GetMapping("/reports/attendance")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ORG_ADMIN', 'MANAGER', 'EMPLOYEE')")
    @Operation(summary = "Per-employee attendance report",
            description = """
                    Scope follows the caller's role. Each Mon–Fri day in the window resolves
                    to present / late / on-leave / absent for each employee. Approved leave
                    counts as leave, never as absence. Defaults to the current month.
                    """)
    public List<AttendanceReportRow> attendance(
            @PathVariable UUID organizationId,
            @Parameter(description = "Inclusive start; defaults to the 1st of this month")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @Parameter(description = "Inclusive end; defaults to today")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @Parameter(description = "Narrow to one employee") @RequestParam(required = false) UUID userId) {
        return reportService.attendanceReport(organizationId, fromDate, toDate, userId);
    }

    @GetMapping(value = "/reports/attendance.csv", produces = CSV)
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ORG_ADMIN', 'MANAGER', 'EMPLOYEE')")
    @Operation(summary = "Attendance report as CSV",
            description = "Same data and scope as the JSON report, as a downloadable file.")
    public ResponseEntity<String> attendanceCsv(
            @PathVariable UUID organizationId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) UUID userId) {
        String csv = reportService.attendanceReportCsv(
                reportService.attendanceReport(organizationId, fromDate, toDate, userId));
        return csvResponse(csv, "attendance-report.csv");
    }

    @GetMapping("/reports/lateness")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ORG_ADMIN', 'MANAGER', 'EMPLOYEE')")
    @Operation(summary = "Per-employee lateness report",
            description = """
                    Scope follows the caller's role. Counts late check-ins against total
                    check-ins and reports the average minutes past the organization's start hour.
                    """)
    public List<LatenessReportRow> lateness(
            @PathVariable UUID organizationId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) UUID userId) {
        return reportService.latenessReport(organizationId, fromDate, toDate, userId);
    }

    @GetMapping(value = "/reports/lateness.csv", produces = CSV)
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ORG_ADMIN', 'MANAGER', 'EMPLOYEE')")
    @Operation(summary = "Lateness report as CSV", description = "Same data and scope as the JSON report.")
    public ResponseEntity<String> latenessCsv(
            @PathVariable UUID organizationId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) UUID userId) {
        String csv = reportService.latenessReportCsv(
                reportService.latenessReport(organizationId, fromDate, toDate, userId));
        return csvResponse(csv, "lateness-report.csv");
    }

    @GetMapping("/reports/leave")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ORG_ADMIN', 'MANAGER', 'EMPLOYEE')")
    @Operation(summary = "Leave usage against entitlement",
            description = """
                    Scope follows the caller's role. One row per employee and leave type,
                    showing entitlement, used, remaining and days still pending approval.
                    """)
    public List<LeaveReportRow> leave(
            @PathVariable UUID organizationId,
            @Parameter(description = "Defaults to the current year") @RequestParam(required = false) Integer year,
            @RequestParam(required = false) UUID userId) {
        return reportService.leaveReport(organizationId, year, userId);
    }

    @GetMapping(value = "/reports/leave.csv", produces = CSV)
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ORG_ADMIN', 'MANAGER', 'EMPLOYEE')")
    @Operation(summary = "Leave report as CSV", description = "Same data and scope as the JSON report.")
    public ResponseEntity<String> leaveCsv(@PathVariable UUID organizationId,
                                           @RequestParam(required = false) Integer year,
                                           @RequestParam(required = false) UUID userId) {
        String csv = reportService.leaveReportCsv(
                reportService.leaveReport(organizationId, year, userId));
        return csvResponse(csv, "leave-report.csv");
    }

    private ResponseEntity<String> csvResponse(String body, String filename) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType(CSV + "; charset=UTF-8"))
                .body(body);
    }
}
