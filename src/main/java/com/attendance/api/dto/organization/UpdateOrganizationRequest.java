package com.attendance.api.dto.organization;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

@Schema(description = "Partial update of org settings; null fields are left unchanged")
public record UpdateOrganizationRequest(
        @Size(max = 150) String name,
        @Schema(example = "Asia/Karachi") String timezone,
        @Min(0) @Max(23) Integer workStartHour,
        @Min(0) @Max(23) Integer workEndHour,
        @Schema(description = "Allow check-in outside a geofence when a reason is supplied")
        Boolean allowManualCheckIn,
        Boolean active
) {
}
