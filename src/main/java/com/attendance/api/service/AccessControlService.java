package com.attendance.api.service;

import com.attendance.api.domain.User;
import com.attendance.api.exception.AccessDeniedBusinessException;
import com.attendance.api.exception.Require;
import com.attendance.api.repository.UserRepository;
import com.attendance.api.security.SecurityUtils;
import com.attendance.api.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

/**
 * Central place for the two authorization questions this API keeps asking:
 * "is this caller inside the tenant they named?" and "which employees may they see?".
 * Every tenant-scoped service routes through here so isolation is enforced in one spot.
 */
@Service
@RequiredArgsConstructor
public class AccessControlService {

    private final UserRepository userRepository;

    /**
     * Validates that the caller may act inside {@code pathOrganizationId} and returns it.
     * A super admin may target any organization; everyone else only their own.
     */
    @NonNull
    public UUID resolveOrganization(@Nullable UUID pathOrganizationId) {
        UserPrincipal principal = SecurityUtils.requirePrincipal();

        if (principal.isSuperAdmin()) {
            if (pathOrganizationId == null) {
                throw new AccessDeniedBusinessException(
                        "A super admin must name the organization to act on");
            }
            return pathOrganizationId;
        }

        UUID own = principal.getOrganizationId();
        if (own == null) {
            throw new AccessDeniedBusinessException("Your account is not attached to an organization");
        }
        if (pathOrganizationId != null && !own.equals(pathOrganizationId)) {
            // Deliberately phrased as a permission error, not "not found", so the
            // caller learns nothing about whether that other tenant exists.
            throw new AccessDeniedBusinessException(
                    "You do not have access to that organization");
        }
        return own;
    }

    /** The caller's own tenant, for endpoints that take no organization in the path. */
    @NonNull
    public UUID currentOrganization() {
        return SecurityUtils.requireOrganizationId();
    }

    /**
     * Which employees the caller may read, expressed as a filter for the repositories:
     * {@code null} means "no restriction" (whole organization), a list means exactly those ids.
     *
     * @param requestedUserId optional narrowing to a single employee
     */
    @Transactional(readOnly = true)
    @Nullable
    public List<UUID> visibleUserIds(@Nullable UUID requestedUserId) {
        UserPrincipal principal = SecurityUtils.requirePrincipal();

        if (principal.isSuperAdmin() || principal.isOrgAdmin()) {
            return requestedUserId == null ? null : List.of(requestedUserId);
        }

        if (principal.isManager()) {
            List<UUID> team = new ArrayList<>(userRepository.findIdsByManagerId(principal.getId()));
            team.add(principal.getId());
            if (requestedUserId == null) {
                return team;
            }
            if (!team.contains(requestedUserId)) {
                throw new AccessDeniedBusinessException(
                        "That employee is not on your team");
            }
            return List.of(requestedUserId);
        }

        // Employees only ever see themselves.
        if (requestedUserId != null && !requestedUserId.equals(principal.getId())) {
            throw new AccessDeniedBusinessException("You may only view your own records");
        }
        return List.of(principal.getId());
    }

    /** Throws unless the caller may read the given employee's records. */
    @Transactional(readOnly = true)
    public void requireCanViewUser(UUID targetUserId) {
        visibleUserIds(targetUserId);
    }

    /**
     * Throws unless the caller may decide on leave for the given employee.
     * Org admins may decide for anyone in their tenant; managers only for direct reports;
     * nobody may decide on their own request.
     */
    @Transactional(readOnly = true)
    public void requireCanDecideFor(User employee) {
        UserPrincipal principal = SecurityUtils.requirePrincipal();

        if (principal.getId().equals(employee.getId())) {
            throw new AccessDeniedBusinessException("You cannot decide on your own leave request");
        }

        if (principal.isSuperAdmin()) {
            throw new AccessDeniedBusinessException(
                    "Leave decisions are made by the organization, not the platform admin");
        }

        if (principal.isOrgAdmin()) {
            return;
        }

        if (principal.isManager()) {
            User employeeManager = employee.getManager();
            UUID managerId = employeeManager == null ? null : employeeManager.getId();
            if (!principal.getId().equals(managerId)) {
                throw new AccessDeniedBusinessException(
                        "You may only decide on leave for your direct reports");
            }
            return;
        }

        throw new AccessDeniedBusinessException("Your role cannot decide leave requests");
    }

    /** Manual attendance override is org-admin only; managers must escalate. */
    public void requireCanOverrideAttendance() {
        UserPrincipal principal = SecurityUtils.requirePrincipal();
        if (!principal.isOrgAdmin() && !principal.isSuperAdmin()) {
            throw new AccessDeniedBusinessException(
                    "Only an organization admin may override attendance; ask your admin to make this change");
        }
    }

    /** Loads a user, confirming they belong to the given tenant. */
    @Transactional(readOnly = true)
    @NonNull
    public User loadUserInOrganization(UUID userId, UUID organizationId) {
        return Require.found(
                userRepository.findByIdAndOrganizationId(userId, organizationId),
                "User", userId);
    }
}
