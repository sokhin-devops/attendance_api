package com.attendance.api.dto.leave;

import com.attendance.api.domain.LeaveBalance;
import com.attendance.api.domain.enums.LeaveType;

import java.util.UUID;

public record LeaveBalanceResponse(
        UUID id,
        UUID userId,
        String userName,
        LeaveType leaveType,
        Integer year,
        Integer totalDays,
        Integer usedDays,
        Integer remainingDays
) {
    public static LeaveBalanceResponse from(LeaveBalance lb) {
        return new LeaveBalanceResponse(
                lb.getId(),
                lb.getUser().getId(),
                lb.getUser().getFullName(),
                lb.getLeaveType(),
                lb.getYear(),
                lb.getTotalDays(),
                lb.getUsedDays(),
                lb.getRemainingDays());
    }
}
