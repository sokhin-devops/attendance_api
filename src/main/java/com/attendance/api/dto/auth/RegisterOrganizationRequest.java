package com.attendance.api.dto.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;

@Schema(description = "Self-service tenant signup: creates the organization and its first org admin")
public record RegisterOrganizationRequest(
        @NotBlank @Size(max = 150)
        @Schema(example = "Acme Corporation")
        String organizationName,

        @NotBlank @Size(min = 2, max = 60)
        @Pattern(regexp = "^[a-z0-9][a-z0-9-]*$",
                 message = "must be lowercase letters, digits and hyphens")
        @Schema(example = "acme")
        String tenantKey,

        @Schema(example = "Asia/Karachi", description = "IANA timezone; defaults to UTC")
        String timezone,

        @Min(0) @Max(23)
        @Schema(example = "9")
        Integer workStartHour,

        @Min(0) @Max(23)
        @Schema(example = "17")
        Integer workEndHour,

        @NotBlank @Email @Size(max = 255)
        @Schema(example = "admin@acme.test")
        String adminEmail,

        @NotBlank
        @Pattern(regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*[0-9])(?=.*[^A-Za-z0-9]).{8,}$",
                 message = "must be at least 8 characters and include upper, lower, digit and symbol")
        @Schema(example = "OrgAdmin@123")
        String adminPassword,

        @NotBlank @Size(max = 80) String adminFirstName,
        @NotBlank @Size(max = 80) String adminLastName
) {
}
