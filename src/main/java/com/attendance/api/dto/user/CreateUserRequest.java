package com.attendance.api.dto.user;

import com.attendance.api.domain.enums.Role;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;

import java.util.List;
import java.util.UUID;

@Schema(description = "Creates a user inside the calling admin's organization")
public record CreateUserRequest(
        @NotBlank @Email @Size(max = 255) String email,

        @NotBlank
        @Pattern(regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*[0-9])(?=.*[^A-Za-z0-9]).{8,}$",
                 message = "must be at least 8 characters and include upper, lower, digit and symbol")
        String password,

        @NotBlank @Size(max = 80) String firstName,
        @NotBlank @Size(max = 80) String lastName,

        @NotNull
        @Schema(description = "ORG_ADMIN, MANAGER or EMPLOYEE. SUPER_ADMIN cannot be created here.")
        Role role,

        @Schema(description = "Manager who approves this user's leave requests")
        UUID managerId,

        @Schema(description = "Locations this user may check in at")
        List<UUID> locationIds
) {
}
