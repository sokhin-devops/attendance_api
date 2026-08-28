package com.attendance.api.controller;

import com.attendance.api.dto.common.PageResponse;
import com.attendance.api.dto.location.CreateLocationRequest;
import com.attendance.api.dto.location.LocationResponse;
import com.attendance.api.dto.location.UpdateLocationRequest;
import com.attendance.api.service.LocationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/locations")
@Tag(name = "Locations", description = "Geofenced work sites")
@RequiredArgsConstructor
public class LocationController {

    private final LocationService locationService;

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ORG_ADMIN', 'MANAGER', 'EMPLOYEE')")
    @Operation(summary = "List locations",
            description = "Any role, scoped to the caller's organization. Paginated.")
    public PageResponse<LocationResponse> list(
            @PathVariable UUID organizationId,
            @Parameter(description = "Filter by active flag") @RequestParam(required = false) Boolean active,
            @Parameter(description = "Matches location name") @RequestParam(required = false) String search,
            @PageableDefault(size = 20, sort = "name", direction = Sort.Direction.ASC) Pageable pageable) {
        return locationService.list(organizationId, active, search, pageable);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ORG_ADMIN')")
    @Operation(summary = "Create a location",
            description = """
                    **SUPER_ADMIN** or **ORG_ADMIN**. The latitude, longitude and radius
                    define the geofence that mobile check-ins are validated against.
                    """)
    public LocationResponse create(@PathVariable UUID organizationId,
                                   @Valid @RequestBody CreateLocationRequest request) {
        return locationService.create(organizationId, request);
    }

    @GetMapping("/{locationId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ORG_ADMIN', 'MANAGER', 'EMPLOYEE')")
    @Operation(summary = "Get one location", description = "Any role within the organization.")
    public LocationResponse get(@PathVariable UUID organizationId, @PathVariable UUID locationId) {
        return locationService.get(organizationId, locationId);
    }

    @PutMapping("/{locationId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ORG_ADMIN')")
    @Operation(summary = "Update a location",
            description = "**SUPER_ADMIN** or **ORG_ADMIN**. Partial update.")
    public LocationResponse update(@PathVariable UUID organizationId,
                                   @PathVariable UUID locationId,
                                   @Valid @RequestBody UpdateLocationRequest request) {
        return locationService.update(organizationId, locationId, request);
    }

    @DeleteMapping("/{locationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ORG_ADMIN')")
    @Operation(summary = "Deactivate a location",
            description = "**SUPER_ADMIN** or **ORG_ADMIN**. Soft delete so historical "
                    + "attendance rows keep pointing at a real location.")
    public void deactivate(@PathVariable UUID organizationId, @PathVariable UUID locationId) {
        locationService.deactivate(organizationId, locationId);
    }
}
