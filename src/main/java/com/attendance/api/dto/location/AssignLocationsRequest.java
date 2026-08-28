package com.attendance.api.dto.location;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

@Schema(description = "Replaces the user's assigned-location set with the supplied ids")
public record AssignLocationsRequest(
        @NotNull List<UUID> locationIds
) {
}
