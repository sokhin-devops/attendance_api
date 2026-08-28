package com.attendance.api.dto.leave;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

@Schema(description = "Org-admin adjustment of an entitlement slot")
public record AdjustLeaveBalanceRequest(
        @NotNull @Min(0)
        @Schema(example = "20", description = "Total entitlement days for the year")
        Integer totalDays,

        @Min(0)
        @Schema(description = "Optional correction of days already consumed")
        Integer usedDays
) {
}
