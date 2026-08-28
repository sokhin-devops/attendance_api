package com.attendance.api.controller;

import com.attendance.api.domain.enums.LeaveType;
import com.attendance.api.dto.leave.AdjustLeaveBalanceRequest;
import com.attendance.api.dto.leave.LeaveBalanceResponse;
import com.attendance.api.service.LeaveBalanceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Leave Balances", description = "Annual entitlements per leave type")
@RequiredArgsConstructor
public class LeaveBalanceController {

    private final LeaveBalanceService leaveBalanceService;

    @GetMapping("/leave-balances/me")
    @PreAuthorize("hasAnyRole('ORG_ADMIN', 'MANAGER', 'EMPLOYEE')")
    @Operation(summary = "The caller's own leave balances",
            description = "Any tenant role. Defaults to the current year.")
    public List<LeaveBalanceResponse> myBalances(
            @Parameter(description = "Defaults to the current year") @RequestParam(required = false) Integer year) {
        return leaveBalanceService.listForCurrentUser(year);
    }

    @GetMapping("/leave-balances/{userId}")
    @PreAuthorize("hasAnyRole('ORG_ADMIN', 'MANAGER', 'EMPLOYEE')")
    @Operation(summary = "One employee's leave balances",
            description = """
                    **ORG_ADMIN** for anyone in the tenant, **MANAGER** for their team,
                    **EMPLOYEE** only for themselves.
                    """)
    public List<LeaveBalanceResponse> balancesForUser(@PathVariable UUID userId,
                                                      @RequestParam(required = false) Integer year) {
        return leaveBalanceService.listForUser(userId, year);
    }

    @PutMapping("/organizations/{organizationId}/leave-balances/{userId}/{leaveType}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ORG_ADMIN')")
    @Operation(summary = "Adjust an entitlement slot",
            description = """
                    **SUPER_ADMIN** or **ORG_ADMIN**. Creates the slot when it does not yet
                    exist. `usedDays` may be corrected too, but never above `totalDays`.
                    """)
    public LeaveBalanceResponse adjust(@PathVariable UUID organizationId,
                                       @PathVariable UUID userId,
                                       @PathVariable LeaveType leaveType,
                                       @RequestParam(required = false) Integer year,
                                       @Valid @RequestBody AdjustLeaveBalanceRequest request) {
        return leaveBalanceService.adjust(organizationId, userId, leaveType, year, request);
    }

    @PostMapping("/organizations/{organizationId}/leave-balances/rollover")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ORG_ADMIN')")
    @Operation(summary = "Create missing entitlement slots for a year",
            description = """
                    **SUPER_ADMIN** or **ORG_ADMIN**. Idempotent: seeds default entitlements
                    for every active user who has no slot yet for that year. Run at the annual
                    rollover. Returns the number of slots created.
                    """)
    public RolloverResult rollover(@PathVariable UUID organizationId, @RequestParam int year) {
        return new RolloverResult(year, leaveBalanceService.rolloverYear(organizationId, year));
    }

    public record RolloverResult(int year, int slotsCreated) {
    }
}
