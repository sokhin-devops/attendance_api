package com.attendance.api.dto.organization;

import com.attendance.api.domain.Organization;

import java.time.Instant;
import java.util.UUID;

public record OrganizationResponse(
        UUID id,
        String name,
        String tenantKey,
        String timezone,
        Integer workStartHour,
        Integer workEndHour,
        boolean allowManualCheckIn,
        boolean active,
        Instant createdAt,
        Long activeUserCount
) {
    public static OrganizationResponse from(Organization o) {
        return from(o, null);
    }

    public static OrganizationResponse from(Organization o, Long activeUserCount) {
        return new OrganizationResponse(
                o.getId(), o.getName(), o.getTenantKey(), o.getTimezone(),
                o.getWorkStartHour(), o.getWorkEndHour(), o.isAllowManualCheckIn(),
                o.isActive(), o.getCreatedAt(), activeUserCount);
    }
}
