package com.attendance.api.dto.leave;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

@Schema(description = "Approval or rejection note")
public record LeaveDecisionRequest(
        @Size(max = 1000)
        @Schema(example = "Approved - team coverage arranged")
        String note
) {
}
