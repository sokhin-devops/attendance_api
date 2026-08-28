package com.attendance.api.dto.location;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;

@Schema(description = "Partial update; null fields are left unchanged")
public record UpdateLocationRequest(
        @Size(max = 150) String name,
        @Size(max = 400) String address,
        @DecimalMin("-90.0") @DecimalMax("90.0") Double latitude,
        @DecimalMin("-180.0") @DecimalMax("180.0") Double longitude,
        @Min(1) @Max(100000) Integer geofenceRadiusMeters,
        Boolean active
) {
}
