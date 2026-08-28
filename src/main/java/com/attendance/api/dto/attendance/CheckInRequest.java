package com.attendance.api.dto.attendance;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;

import java.util.UUID;

@Schema(description = "GPS-verified check-in sent from the mobile app")
public record CheckInRequest(
        @NotNull
        @Schema(description = "Location the employee is checking in at")
        UUID locationId,

        @NotNull @DecimalMin("-90.0") @DecimalMax("90.0")
        @Schema(example = "24.8607", description = "Device latitude at check-in")
        Double latitude,

        @NotNull @DecimalMin("-180.0") @DecimalMax("180.0")
        @Schema(example = "67.0011", description = "Device longitude at check-in")
        Double longitude,

        @Schema(example = "12.5", description = "Device-reported GPS accuracy in meters")
        Double gpsAccuracyMeters,

        @Size(max = 500)
        @Schema(description = "Justification for checking in outside the geofence. Only "
                + "honoured when the organization allows manual check-in.")
        String manualReason
) {
}
