package com.attendance.api.dto.location;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;

@Schema(description = "A geofenced work site")
public record CreateLocationRequest(
        @NotBlank @Size(max = 150)
        @Schema(example = "Head Office") String name,

        @Size(max = 400) String address,

        @NotNull @DecimalMin("-90.0") @DecimalMax("90.0")
        @Schema(example = "24.8607") Double latitude,

        @NotNull @DecimalMin("-180.0") @DecimalMax("180.0")
        @Schema(example = "67.0011") Double longitude,

        @NotNull @Min(1) @Max(100000)
        @Schema(example = "150", description = "Geofence radius in meters")
        Integer geofenceRadiusMeters
) {
}
