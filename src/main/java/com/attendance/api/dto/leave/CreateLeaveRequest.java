package com.attendance.api.dto.leave;

import com.attendance.api.domain.enums.LeaveType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.UUID;

@Schema(description = "Leave request submission")
public record CreateLeaveRequest(
        @NotNull LeaveType leaveType,

        @NotNull
        @Schema(example = "2026-09-01") LocalDate fromDate,

        @NotNull
        @Schema(example = "2026-09-03") LocalDate toDate,

        @Size(max = 1000) String reason,

        @Schema(description = "Admins and managers may file on an employee's behalf. "
                + "Employees must omit this or set their own id.")
        UUID employeeId
) {
}
