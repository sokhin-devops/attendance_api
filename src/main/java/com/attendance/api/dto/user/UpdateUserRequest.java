package com.attendance.api.dto.user;

import com.attendance.api.domain.enums.Role;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

@Schema(description = "Partial update; null fields are left unchanged")
public record UpdateUserRequest(
        @Size(max = 80) String firstName,
        @Size(max = 80) String lastName,
        Role role,
        UUID managerId,
        Boolean active,
        @Schema(description = "When present, replaces the user's full location assignment set")
        List<UUID> locationIds
) {
}
