package com.attendance.api.dto.user;

import com.attendance.api.domain.Organization;
import com.attendance.api.domain.User;
import com.attendance.api.domain.enums.Role;

import java.time.Instant;
import java.util.UUID;

public record UserResponse(
        UUID id,
        UUID organizationId,
        String organizationName,
        String email,
        String firstName,
        String lastName,
        String fullName,
        Role role,
        UUID managerId,
        String managerName,
        boolean active,
        Instant lastLoginAt,
        Instant createdAt
) {
    public static UserResponse from(User u) {
        // Read each nullable association once: calling the getter twice would hit the
        // lazy proxy again and leaves the null-check unprovable to static analysis.
        Organization organization = u.getOrganization();
        User manager = u.getManager();

        return new UserResponse(
                u.getId(),
                organization == null ? null : organization.getId(),
                organization == null ? null : organization.getName(),
                u.getEmail(),
                u.getFirstName(),
                u.getLastName(),
                u.getFullName(),
                u.getRole(),
                manager == null ? null : manager.getId(),
                manager == null ? null : manager.getFullName(),
                u.isActive(),
                u.getLastLoginAt(),
                u.getCreatedAt());
    }
}
