package com.attendance.api.controller;

import com.attendance.api.dto.common.PageResponse;
import com.attendance.api.dto.organization.CreateOrganizationRequest;
import com.attendance.api.dto.organization.OrganizationResponse;
import com.attendance.api.dto.organization.UpdateOrganizationRequest;
import com.attendance.api.service.OrganizationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/organizations")
@Tag(name = "Organizations", description = "Tenant provisioning and settings")
@RequiredArgsConstructor
public class OrganizationController {

    private final OrganizationService organizationService;

    @GetMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "List all organizations",
            description = "**SUPER_ADMIN only.** Paginated, optionally filtered by name or tenant key.")
    public PageResponse<OrganizationResponse> list(
            @Parameter(description = "Matches organization name or tenant key")
            @RequestParam(required = false) String search,
            @PageableDefault(size = 20, sort = "name", direction = Sort.Direction.ASC) Pageable pageable) {
        return organizationService.list(search, pageable);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Provision a new organization and its first admin",
            description = "**SUPER_ADMIN only.** For self-service signup use "
                    + "`POST /api/v1/auth/register-organization` instead.")
    public OrganizationResponse create(@Valid @RequestBody CreateOrganizationRequest request) {
        return organizationService.create(request);
    }

    @GetMapping("/{organizationId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ORG_ADMIN', 'MANAGER', 'EMPLOYEE')")
    @Operation(summary = "Get one organization",
            description = """
                    **SUPER_ADMIN** may read any organization. Everyone else may only read
                    their own; naming a different one returns 403.
                    """)
    public OrganizationResponse get(@PathVariable UUID organizationId) {
        return organizationService.get(organizationId);
    }

    @PutMapping("/{organizationId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ORG_ADMIN')")
    @Operation(summary = "Update organization settings",
            description = """
                    **SUPER_ADMIN** or **ORG_ADMIN** (own organization only). Controls the
                    working-hours window used for lateness, the timezone used to resolve
                    "today", and whether out-of-geofence manual check-in is permitted.
                    """)
    public OrganizationResponse update(@PathVariable UUID organizationId,
                                       @Valid @RequestBody UpdateOrganizationRequest request) {
        return organizationService.update(organizationId, request);
    }

    @DeleteMapping("/{organizationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Deactivate an organization",
            description = "**SUPER_ADMIN only.** Soft delete: users can no longer sign in, "
                    + "but attendance and leave history is retained.")
    public void deactivate(@PathVariable @NonNull UUID organizationId) {
        organizationService.deactivate(organizationId);
    }
}
