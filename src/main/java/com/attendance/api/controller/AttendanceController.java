package com.attendance.api.controller;

import com.attendance.api.dto.attendance.*;
import com.attendance.api.dto.common.PageResponse;
import com.attendance.api.dto.location.LocationResponse;
import com.attendance.api.service.AttendanceService;
import com.attendance.api.service.LocationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/attendance")
@Tag(name = "Attendance", description = "GPS check-in/out, history and admin override")
@RequiredArgsConstructor
public class AttendanceController {

    private final AttendanceService attendanceService;
    private final LocationService locationService;

    @PostMapping("/check-in")
    @PreAuthorize("hasAnyRole('ORG_ADMIN', 'MANAGER', 'EMPLOYEE')")
    @Operation(summary = "Check in at an assigned location",
            description = """
                    Any tenant role, for the caller themselves. The device coordinates are
                    compared against the location's geofence using great-circle distance.

                    Rejected with **403** when outside the radius, unless the organization
                    has `allowManualCheckIn` enabled **and** a `manualReason` is supplied —
                    in which case the record is flagged as a manual override. The 403 body
                    reports the actual distance and the allowed radius.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Checked in"),
            @ApiResponse(responseCode = "400", description = "Location inactive, or on approved leave today"),
            @ApiResponse(responseCode = "403", description = "Outside geofence, or not assigned to the location"),
            @ApiResponse(responseCode = "409", description = "Already checked in today")
    })
    public AttendanceResponse checkIn(@Valid @RequestBody CheckInRequest request) {
        return attendanceService.checkIn(request);
    }

    @PostMapping("/check-out")
    @PreAuthorize("hasAnyRole('ORG_ADMIN', 'MANAGER', 'EMPLOYEE')")
    @Operation(summary = "Check out of today's open check-in",
            description = "Any tenant role, for the caller themselves.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Checked out"),
            @ApiResponse(responseCode = "400", description = "Not checked in today"),
            @ApiResponse(responseCode = "409", description = "Already checked out today")
    })
    public AttendanceResponse checkOut(@RequestBody(required = false) @Valid CheckOutRequest request) {
        return attendanceService.checkOut(request);
    }

    @GetMapping("/status")
    @PreAuthorize("hasAnyRole('ORG_ADMIN', 'MANAGER', 'EMPLOYEE')")
    @Operation(summary = "Today's check-in state for the caller",
            description = """
                    Any tenant role. One call for the mobile home screen: whether the caller
                    has checked in or out today, whether they are on approved leave, whether
                    manual check-in is permitted, and the locations they may use.
                    """)
    public AttendanceStatusResponse status() {
        return attendanceService.currentStatus();
    }

    @GetMapping("/my-locations")
    @PreAuthorize("hasAnyRole('ORG_ADMIN', 'MANAGER', 'EMPLOYEE')")
    @Operation(summary = "Locations the caller may check in at",
            description = "Any tenant role, for the caller themselves.")
    public List<LocationResponse> myLocations() {
        return locationService.myLocations();
    }

    @GetMapping("/me")
    @PreAuthorize("hasAnyRole('ORG_ADMIN', 'MANAGER', 'EMPLOYEE')")
    @Operation(summary = "The caller's own attendance history",
            description = "Any tenant role. Paginated, newest first by default.")
    public PageResponse<AttendanceResponse> myHistory(
            @Parameter(description = "Inclusive start date") @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @Parameter(description = "Inclusive end date") @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @PageableDefault(size = 20, sort = "workDate", direction = Sort.Direction.DESC) Pageable pageable) {
        return attendanceService.myHistory(fromDate, toDate, pageable);
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ORG_ADMIN', 'MANAGER', 'EMPLOYEE')")
    @Operation(summary = "Attendance history",
            description = """
                    **ORG_ADMIN** sees the whole organization, **MANAGER** their own team,
                    **EMPLOYEE** only themselves. Naming a `userId` outside your visibility
                    returns 403. Paginated and filterable by date range and location.
                    """)
    public PageResponse<AttendanceResponse> history(
            @Parameter(description = "Narrow to one employee") @RequestParam(required = false) UUID userId,
            @Parameter(description = "Narrow to one location") @RequestParam(required = false) UUID locationId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @PageableDefault(size = 20, sort = "workDate", direction = Sort.Direction.DESC) Pageable pageable) {
        return attendanceService.history(userId, locationId, fromDate, toDate, pageable);
    }

    @GetMapping("/{attendanceId}")
    @PreAuthorize("hasAnyRole('ORG_ADMIN', 'MANAGER', 'EMPLOYEE')")
    @Operation(summary = "Get one attendance record",
            description = "Subject to the same visibility rules as the history listing.")
    public AttendanceResponse get(@PathVariable UUID attendanceId) {
        return attendanceService.get(attendanceId);
    }
}
