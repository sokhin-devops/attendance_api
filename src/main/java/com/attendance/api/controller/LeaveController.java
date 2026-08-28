package com.attendance.api.controller;

import com.attendance.api.domain.enums.LeaveStatus;
import com.attendance.api.dto.common.PageResponse;
import com.attendance.api.dto.leave.CreateLeaveRequest;
import com.attendance.api.dto.leave.LeaveDecisionRequest;
import com.attendance.api.dto.leave.LeaveRequestResponse;
import com.attendance.api.service.LeaveService;
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
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/leave-requests")
@Tag(name = "Leave Requests", description = "Submit, approve, reject and cancel leave")
@RequiredArgsConstructor
public class LeaveController {

    private final LeaveService leaveService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ORG_ADMIN', 'MANAGER', 'EMPLOYEE')")
    @Operation(summary = "Submit a leave request",
            description = """
                    Any tenant role. An **EMPLOYEE** may only file for themselves; a
                    **MANAGER** may also file for their team and an **ORG_ADMIN** for anyone
                    in the tenant, by setting `employeeId`.

                    Balance is checked at submission so the employee learns early, and again
                    debited at approval. Overlapping pending or approved leave is rejected.
                    The request is routed to the employee's manager, or to every org admin
                    when they have none.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Request submitted and approvers notified"),
            @ApiResponse(responseCode = "400", description = "Invalid range or insufficient balance"),
            @ApiResponse(responseCode = "409", description = "Overlaps an existing pending or approved request")
    })
    public LeaveRequestResponse submit(@Valid @RequestBody CreateLeaveRequest request) {
        return leaveService.submit(request);
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ORG_ADMIN', 'MANAGER', 'EMPLOYEE')")
    @Operation(summary = "List leave requests",
            description = """
                    **ORG_ADMIN** sees the whole organization, **MANAGER** their own team,
                    **EMPLOYEE** only themselves. Filter by status to build an approval queue.
                    """)
    public PageResponse<LeaveRequestResponse> list(
            @Parameter(description = "Narrow to one employee") @RequestParam(required = false) UUID employeeId,
            @Parameter(description = "Filter by status") @RequestParam(required = false) LeaveStatus status,
            @Parameter(description = "Requests overlapping on or after this date")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @Parameter(description = "Requests overlapping on or before this date")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return leaveService.list(employeeId, status, fromDate, toDate, pageable);
    }

    @GetMapping("/me")
    @PreAuthorize("hasAnyRole('ORG_ADMIN', 'MANAGER', 'EMPLOYEE')")
    @Operation(summary = "The caller's own leave requests", description = "Any tenant role.")
    public PageResponse<LeaveRequestResponse> myRequests(
            @RequestParam(required = false) LeaveStatus status,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return leaveService.myRequests(status, pageable);
    }

    @GetMapping("/{requestId}")
    @PreAuthorize("hasAnyRole('ORG_ADMIN', 'MANAGER', 'EMPLOYEE')")
    @Operation(summary = "Get one leave request",
            description = "Subject to the same visibility rules as the listing.")
    public LeaveRequestResponse get(@PathVariable UUID requestId) {
        return leaveService.get(requestId);
    }

    @PutMapping("/{requestId}/approve")
    @PreAuthorize("hasAnyRole('ORG_ADMIN', 'MANAGER')")
    @Operation(summary = "Approve a pending request",
            description = """
                    **ORG_ADMIN** for anyone in the tenant; **MANAGER** for direct reports
                    only. Nobody may decide on their own request.

                    On approval the days are debited from the matching balance; an
                    insufficient balance aborts the approval with 400.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Approved and employee notified"),
            @ApiResponse(responseCode = "400", description = "Not pending, or insufficient balance"),
            @ApiResponse(responseCode = "403", description = "Not your report, or your own request")
    })
    public LeaveRequestResponse approve(@PathVariable UUID requestId,
                                        @RequestBody(required = false) @Valid LeaveDecisionRequest decision) {
        return leaveService.approve(requestId, decision);
    }

    @PutMapping("/{requestId}/reject")
    @PreAuthorize("hasAnyRole('ORG_ADMIN', 'MANAGER')")
    @Operation(summary = "Reject a pending request",
            description = "Same authority rules as approval. No balance is debited.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Rejected and employee notified"),
            @ApiResponse(responseCode = "400", description = "Request is not pending"),
            @ApiResponse(responseCode = "403", description = "Not your report, or your own request")
    })
    public LeaveRequestResponse reject(@PathVariable UUID requestId,
                                       @RequestBody(required = false) @Valid LeaveDecisionRequest decision) {
        return leaveService.reject(requestId, decision);
    }

    @PutMapping("/{requestId}/cancel")
    @PreAuthorize("hasAnyRole('ORG_ADMIN', 'MANAGER', 'EMPLOYEE')")
    @Operation(summary = "Cancel a request",
            description = """
                    The requesting **EMPLOYEE** may withdraw their own request while it is
                    still pending. An **ORG_ADMIN** may also cancel an already-approved
                    request, which credits the days back to the balance.
                    """)
    public LeaveRequestResponse cancel(@PathVariable UUID requestId) {
        return leaveService.cancel(requestId);
    }
}
