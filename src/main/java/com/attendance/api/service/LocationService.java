package com.attendance.api.service;

import com.attendance.api.domain.Location;
import com.attendance.api.domain.Organization;
import com.attendance.api.dto.common.PageResponse;
import com.attendance.api.dto.location.CreateLocationRequest;
import com.attendance.api.dto.location.LocationResponse;
import com.attendance.api.dto.location.UpdateLocationRequest;
import com.attendance.api.exception.ConflictException;
import com.attendance.api.exception.Require;
import com.attendance.api.repository.LocationRepository;
import com.attendance.api.repository.QueryParams;
import com.attendance.api.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.UUID;
import org.springframework.lang.NonNull;

/** Geofenced work sites, scoped to one organization. */
@Service
@RequiredArgsConstructor
@Slf4j
public class LocationService {

    private final LocationRepository locationRepository;
    private final OrganizationService organizationService;
    private final AccessControlService accessControl;

    @Transactional(readOnly = true)
    public PageResponse<LocationResponse> list(UUID organizationId, Boolean active,
                                               String search, Pageable pageable) {
        UUID orgId = accessControl.resolveOrganization(organizationId);
        return PageResponse.of(
                locationRepository.searchInOrganization(orgId,
                        active == null, Boolean.TRUE.equals(active),
                        QueryParams.likePattern(search), pageable),
                LocationResponse::from);
    }

    @Transactional(readOnly = true)
    public LocationResponse get(UUID organizationId, UUID locationId) {
        UUID orgId = accessControl.resolveOrganization(organizationId);
        return LocationResponse.from(requireLocation(locationId, orgId));
    }

    @Transactional
    public LocationResponse create(UUID organizationId, CreateLocationRequest request) {
        UUID orgId = accessControl.resolveOrganization(organizationId);
        Organization organization = organizationService.requireOrganization(orgId);

        String name = request.name().trim();
        if (locationRepository.existsByOrganizationIdAndNameIgnoreCase(orgId, name)) {
            throw new ConflictException("A location named \"" + name + "\" already exists");
        }

        Location location = locationRepository.save(Location.builder()
                .organization(organization)
                .name(name)
                .address(StringUtils.hasText(request.address()) ? request.address().trim() : null)
                .latitude(request.latitude())
                .longitude(request.longitude())
                .geofenceRadiusMeters(request.geofenceRadiusMeters())
                .active(true)
                .build());

        log.info("Location {} created in org {} at ({}, {}) r={}m",
                location.getName(), orgId, location.getLatitude(),
                location.getLongitude(), location.getGeofenceRadiusMeters());

        return LocationResponse.from(location);
    }

    @Transactional
    public LocationResponse update(UUID organizationId, UUID locationId,
                                   UpdateLocationRequest request) {
        UUID orgId = accessControl.resolveOrganization(organizationId);
        Location location = requireLocation(locationId, orgId);

        if (StringUtils.hasText(request.name())) {
            String newName = request.name().trim();
            if (!newName.equalsIgnoreCase(location.getName())
                    && locationRepository.existsByOrganizationIdAndNameIgnoreCase(orgId, newName)) {
                throw new ConflictException("A location named \"" + newName + "\" already exists");
            }
            location.setName(newName);
        }
        if (request.address() != null) {
            location.setAddress(StringUtils.hasText(request.address()) ? request.address().trim() : null);
        }
        if (request.latitude() != null) {
            location.setLatitude(request.latitude());
        }
        if (request.longitude() != null) {
            location.setLongitude(request.longitude());
        }
        if (request.geofenceRadiusMeters() != null) {
            location.setGeofenceRadiusMeters(request.geofenceRadiusMeters());
        }
        if (request.active() != null) {
            location.setActive(request.active());
        }

        Location saved = locationRepository.save(location);
        log.info("Location {} updated in org {}", locationId, orgId);
        return LocationResponse.from(saved);
    }

    /** Deactivation keeps historical attendance rows pointing at a real location. */
    @Transactional
    public void deactivate(UUID organizationId, UUID locationId) {
        UUID orgId = accessControl.resolveOrganization(organizationId);
        Location location = requireLocation(locationId, orgId);
        location.setActive(false);
        locationRepository.save(location);
        log.info("Location {} deactivated in org {}", locationId, orgId);
    }

    /** The locations the calling employee may check in at. */
    @Transactional(readOnly = true)
    public List<LocationResponse> myLocations() {
        return locationRepository.findAssignedToUser(SecurityUtils.currentUserId()).stream()
                .map(LocationResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    @NonNull
    public Location requireLocation(UUID locationId, UUID organizationId) {
        return Require.found(
                locationRepository.findByIdAndOrganizationId(locationId, organizationId),
                "Location", locationId);
    }
}
