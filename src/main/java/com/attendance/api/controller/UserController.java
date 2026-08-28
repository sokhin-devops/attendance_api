package com.attendance.api.controller;

import com.attendance.api.domain.enums.Role;
import com.attendance.api.dto.common.PageResponse;
import com.attendance.api.dto.location.AssignLocationsRequest;
import com.attendance.api.dto.location.LocationResponse;
import com.attendance.api.dto.user.CreateUserRequest;
import com.attendance.api.dto.user.UpdateUserRequest;
import com.attendance.api.dto.user.UserResponse;
import com.attendance.api.service.UserService;
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

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/users")
@Tag(name = "Users", description = "Tenant user directory and location assignment")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ORG_ADMIN', 'MANAGER')")
    @Operation(summary = "List users in an organization",
            description = """
                    **SUPER_ADMIN**, **ORG_ADMIN** or **MANAGER**. Results are always confined
                    to the organization in the path, and a non-super-admin may only name their
                    own organization.
                    """)
    public PageResponse<UserResponse> list(
            @PathVariable UUID organizationId,
            @Parameter(description = "Filter by role") @RequestParam(required = false) Role role,
            @Parameter(description = "Filter by active flag") @RequestParam(required = false) Boolean active,
            @Parameter(description = "Matches email, first or last name")
            @RequestParam(required = false) String search,
            @PageableDefault(size = 20, sort = "firstName", direction = Sort.Direction.ASC) Pageable pageable) {
        return userService.list(organizationId, role, active, search, pageable);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ORG_ADMIN')")
    @Operation(summary = "Create a user",
            description = """
                    **SUPER_ADMIN** or **ORG_ADMIN**. Seeds default leave balances for the
                    current year. `SUPER_ADMIN` cannot be created through this endpoint —
                    it is a platform role with no organization.
                    """)
    public UserResponse create(@PathVariable UUID organizationId,
                               @Valid @RequestBody CreateUserRequest request) {
        return userService.create(organizationId, request);
    }

    @GetMapping("/{userId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ORG_ADMIN', 'MANAGER', 'EMPLOYEE')")
    @Operation(summary = "Get one user",
            description = """
                    **ORG_ADMIN** may read anyone in the tenant, **MANAGER** their own team,
                    **EMPLOYEE** only themselves.
                    """)
    public UserResponse get(@PathVariable UUID organizationId, @PathVariable UUID userId) {
        return userService.get(organizationId, userId);
    }

    @PutMapping("/{userId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ORG_ADMIN')")
    @Operation(summary = "Update a user",
            description = """
                    **SUPER_ADMIN** or **ORG_ADMIN**. Partial update. Deactivating a user
                    revokes their sessions and notifies them. An organization must always
                    keep at least one active `ORG_ADMIN`.
                    """)
    public UserResponse update(@PathVariable UUID organizationId,
                               @PathVariable UUID userId,
                               @Valid @RequestBody UpdateUserRequest request) {
        return userService.update(organizationId, userId, request);
    }

    @DeleteMapping("/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ORG_ADMIN')")
    @Operation(summary = "Deactivate a user",
            description = "**SUPER_ADMIN** or **ORG_ADMIN**. Soft delete so attendance "
                    + "history survives. You cannot deactivate your own account.")
    public void deactivate(@PathVariable UUID organizationId, @PathVariable UUID userId) {
        userService.deactivate(organizationId, userId);
    }

    @GetMapping("/{userId}/team")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ORG_ADMIN', 'MANAGER')")
    @Operation(summary = "List a manager's direct reports",
            description = "**ORG_ADMIN** for any manager; **MANAGER** for themselves.")
    public List<UserResponse> team(@PathVariable UUID organizationId, @PathVariable UUID userId) {
        return userService.listTeam(organizationId, userId);
    }

    @GetMapping("/{userId}/locations")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ORG_ADMIN', 'MANAGER', 'EMPLOYEE')")
    @Operation(summary = "List the locations a user may check in at",
            description = "Same visibility rules as reading the user.")
    public List<LocationResponse> locations(@PathVariable UUID organizationId,
                                            @PathVariable UUID userId) {
        return userService.listAssignedLocations(organizationId, userId);
    }

    @PutMapping("/{userId}/locations")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ORG_ADMIN')")
    @Operation(summary = "Replace a user's location assignments",
            description = """
                    **SUPER_ADMIN** or **ORG_ADMIN**. Sends the complete desired set; the
                    previous assignments are replaced. Every id must belong to this
                    organization and be active.
                    """)
    public List<LocationResponse> assignLocations(@PathVariable UUID organizationId,
                                                  @PathVariable UUID userId,
                                                  @Valid @RequestBody AssignLocationsRequest request) {
        return userService.assignLocations(organizationId, userId, request.locationIds());
    }
}
