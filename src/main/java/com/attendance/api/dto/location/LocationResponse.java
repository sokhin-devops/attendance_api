package com.attendance.api.dto.location;

import com.attendance.api.domain.Location;

import java.time.Instant;
import java.util.UUID;

public record LocationResponse(
        UUID id,
        UUID organizationId,
        String name,
        String address,
        Double latitude,
        Double longitude,
        Integer geofenceRadiusMeters,
        boolean active,
        Instant createdAt
) {
    public static LocationResponse from(Location l) {
        return new LocationResponse(
                l.getId(), l.getOrganization().getId(), l.getName(), l.getAddress(),
                l.getLatitude(), l.getLongitude(), l.getGeofenceRadiusMeters(),
                l.isActive(), l.getCreatedAt());
    }
}
