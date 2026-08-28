package com.attendance.api.dto.organization;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;

/** Super-admin path for provisioning a tenant and its first org admin. */
@Schema(description = "Super-admin organization provisioning")
public record CreateOrganizationRequest(
        @NotBlank @Size(max = 150) String name,

        @NotBlank @Size(min = 2, max = 60)
        @Pattern(regexp = "^[a-z0-9][a-z0-9-]*$",
                 message = "must be lowercase letters, digits and hyphens")
        String tenantKey,

        String timezone,
        @Min(0) @Max(23) Integer workStartHour,
        @Min(0) @Max(23) Integer workEndHour,

        @NotBlank @Email @Size(max = 255) String adminEmail,

        @NotBlank
        @Pattern(regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*[0-9])(?=.*[^A-Za-z0-9]).{8,}$",
                 message = "must be at least 8 characters and include upper, lower, digit and symbol")
        String adminPassword,

        @NotBlank @Size(max = 80) String adminFirstName,
        @NotBlank @Size(max = 80) String adminLastName
) {
}
