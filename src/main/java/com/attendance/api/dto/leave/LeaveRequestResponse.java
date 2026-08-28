package com.attendance.api.dto.leave;

import com.attendance.api.domain.LeaveRequest;
import com.attendance.api.domain.User;
import com.attendance.api.domain.enums.LeaveStatus;
import com.attendance.api.domain.enums.LeaveType;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record LeaveRequestResponse(
        UUID id,
        UUID employeeId,
        String employeeName,
        LeaveType leaveType,
        LocalDate fromDate,
        LocalDate toDate,
        Integer daysRequested,
        String reason,
        LeaveStatus status,
        UUID requestedById,
        String requestedByName,
        UUID approvedById,
        String approvedByName,
        String decisionNote,
        Instant decidedAt,
        Instant createdAt
) {
    public static LeaveRequestResponse from(LeaveRequest lr) {
        // Read the nullable approver once instead of twice.
        User approvedBy = lr.getApprovedBy();

        return new LeaveRequestResponse(
                lr.getId(),
                lr.getEmployee().getId(),
                lr.getEmployee().getFullName(),
                lr.getLeaveType(),
                lr.getFromDate(),
                lr.getToDate(),
                lr.getDaysRequested(),
                lr.getReason(),
                lr.getStatus(),
                lr.getRequestedBy().getId(),
                lr.getRequestedBy().getFullName(),
                approvedBy == null ? null : approvedBy.getId(),
                approvedBy == null ? null : approvedBy.getFullName(),
                lr.getDecisionNote(),
                lr.getDecidedAt(),
                lr.getCreatedAt());
    }
}
