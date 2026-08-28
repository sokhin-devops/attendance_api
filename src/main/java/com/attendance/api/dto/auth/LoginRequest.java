package com.attendance.api.dto.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Credentials for password login")
public record LoginRequest(
        @NotBlank @Email
        @Schema(example = "admin@acme.test")
        String email,

        @NotBlank
        @Schema(example = "OrgAdmin@123")
        String password,

        @Schema(description = "Tenant key. Required only when the same email exists in "
                + "more than one organization.", example = "acme")
        String tenantKey
) {
}
