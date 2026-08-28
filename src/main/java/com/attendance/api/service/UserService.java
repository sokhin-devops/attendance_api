package com.attendance.api.service;

import com.attendance.api.domain.Location;
import com.attendance.api.domain.Organization;
import com.attendance.api.domain.User;
import com.attendance.api.domain.UserLocation;
import com.attendance.api.domain.enums.NotificationType;
import com.attendance.api.domain.enums.Role;
import com.attendance.api.dto.common.PageResponse;
import com.attendance.api.dto.location.LocationResponse;
import com.attendance.api.dto.user.CreateUserRequest;
import com.attendance.api.dto.user.UpdateUserRequest;
import com.attendance.api.dto.user.UserResponse;
import com.attendance.api.exception.BusinessRuleException;
import com.attendance.api.exception.ConflictException;
import com.attendance.api.exception.Require;
import com.attendance.api.repository.*;
import com.attendance.api.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.UUID;

/** Tenant user directory: creation, updates, deactivation and location assignment. */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {

    private final UserRepository userRepository;
    private final LocationRepository locationRepository;
    private final UserLocationRepository userLocationRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final OrganizationService organizationService;
    private final LeaveBalanceService leaveBalanceService;
    private final NotificationService notificationService;
    private final AccessControlService accessControl;
    private final PasswordEncoder passwordEncoder;

    @Transactional(readOnly = true)
    public PageResponse<UserResponse> list(UUID organizationId, Role role, Boolean active,
                                           String search, Pageable pageable) {
        UUID orgId = accessControl.resolveOrganization(organizationId);
        return PageResponse.of(
                userRepository.searchInOrganization(orgId,
                        role == null, role,
                        active == null, Boolean.TRUE.equals(active),
                        QueryParams.likePattern(search), pageable),
                UserResponse::from);
    }

    @Transactional(readOnly = true)
    public UserResponse get(UUID organizationId, UUID userId) {
        UUID orgId = accessControl.resolveOrganization(organizationId);
        accessControl.requireCanViewUser(userId);
        return UserResponse.from(accessControl.loadUserInOrganization(userId, orgId));
    }

    @Transactional
    public UserResponse create(UUID organizationId, CreateUserRequest request) {
        UUID orgId = accessControl.resolveOrganization(organizationId);
        Organization organization = organizationService.requireOrganization(orgId);

        if (request.role() == Role.SUPER_ADMIN) {
            throw new BusinessRuleException(
                    "SUPER_ADMIN is a platform role and cannot be created inside an organization");
        }

        String email = request.email().trim().toLowerCase();
        if (userRepository.findByEmailAndOrganizationId(email, orgId).isPresent()) {
            throw new ConflictException("A user with that email already exists in this organization");
        }

        User manager = resolveManager(request.managerId(), orgId);

        User user = userRepository.save(User.builder()
                .organization(organization)
                .email(email)
                .passwordHash(passwordEncoder.encode(request.password()))
                .firstName(request.firstName().trim())
                .lastName(request.lastName().trim())
                .role(request.role())
                .manager(manager)
                .active(true)
                .build());

        if (request.locationIds() != null && !request.locationIds().isEmpty()) {
            replaceLocationAssignments(user, orgId, request.locationIds());
        }
        leaveBalanceService.seedDefaultBalances(user);

        log.info("User {} created in org {} with role {}", user.getEmail(), orgId, user.getRole());
        return UserResponse.from(user);
    }

    @Transactional
    public UserResponse update(UUID organizationId, UUID userId, UpdateUserRequest request) {
        UUID orgId = accessControl.resolveOrganization(organizationId);
        User user = accessControl.loadUserInOrganization(userId, orgId);

        if (StringUtils.hasText(request.firstName())) {
            user.setFirstName(request.firstName().trim());
        }
        if (StringUtils.hasText(request.lastName())) {
            user.setLastName(request.lastName().trim());
        }
        if (request.role() != null) {
            if (request.role() == Role.SUPER_ADMIN) {
                throw new BusinessRuleException("A tenant user cannot be promoted to SUPER_ADMIN");
            }
            guardLastOrgAdmin(user, request.role(), orgId);
            user.setRole(request.role());
        }
        if (request.managerId() != null) {
            if (request.managerId().equals(userId)) {
                throw new BusinessRuleException("A user cannot be their own manager");
            }
            user.setManager(resolveManager(request.managerId(), orgId));
        }
        if (request.active() != null) {
            applyActiveChange(user, request.active(), orgId);
        }
        if (request.locationIds() != null) {
            replaceLocationAssignments(user, orgId, request.locationIds());
        }

        User saved = userRepository.save(user);
        log.info("User {} updated in org {}", userId, orgId);
        return UserResponse.from(saved);
    }

    /** Deactivation, not deletion — attendance and leave history must remain intact. */
    @Transactional
    public void deactivate(UUID organizationId, UUID userId) {
        UUID orgId = accessControl.resolveOrganization(organizationId);
        User user = accessControl.loadUserInOrganization(userId, orgId);

        if (userId.equals(SecurityUtils.currentUserId())) {
            throw new BusinessRuleException("You cannot deactivate your own account");
        }
        applyActiveChange(user, false, orgId);
        userRepository.save(user);
    }

    @Transactional(readOnly = true)
    public List<UserResponse> listTeam(UUID organizationId, UUID managerId) {
        // Role visibility alone is not enough: an org admin may name "any user", so the
        // manager must also be confirmed to live in the caller's own tenant.
        UUID orgId = accessControl.resolveOrganization(organizationId);
        accessControl.loadUserInOrganization(managerId, orgId);
        accessControl.requireCanViewUser(managerId);
        return userRepository.findByManagerId(managerId).stream().map(UserResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<LocationResponse> listAssignedLocations(UUID organizationId, UUID userId) {
        UUID orgId = accessControl.resolveOrganization(organizationId);
        accessControl.requireCanViewUser(userId);
        accessControl.loadUserInOrganization(userId, orgId);
        return locationRepository.findAssignedToUser(userId).stream()
                .map(LocationResponse::from)
                .toList();
    }

    @Transactional
    public List<LocationResponse> assignLocations(UUID organizationId, UUID userId,
                                                  List<UUID> locationIds) {
        UUID orgId = accessControl.resolveOrganization(organizationId);
        User user = accessControl.loadUserInOrganization(userId, orgId);
        replaceLocationAssignments(user, orgId, locationIds);
        return locationRepository.findAssignedToUser(userId).stream()
                .map(LocationResponse::from)
                .toList();
    }

    /**
     * Replaces the user's whole assignment set. Every id is verified to belong to the
     * caller's tenant first, so a crafted request cannot borrow another org's location.
     */
    private void replaceLocationAssignments(User user, UUID organizationId, List<UUID> locationIds) {
        for (UUID locationId : locationIds) {
            Location location = Require.found(
                    locationRepository.findByIdAndOrganizationId(locationId, organizationId),
                    "Location", locationId);
            if (!location.isActive()) {
                throw new BusinessRuleException(
                        "Location is inactive and cannot be assigned: " + location.getName());
            }
        }

        userLocationRepository.deleteByUserId(user.getId());
        userLocationRepository.flush();

        locationIds.stream().distinct().forEach(locationId ->
                userLocationRepository.save(UserLocation.builder()
                        .userId(user.getId())
                        .locationId(locationId)
                        .build()));

        log.debug("User {} assigned to {} location(s)", user.getId(), locationIds.size());
    }

    private User resolveManager(UUID managerId, UUID organizationId) {
        if (managerId == null) {
            return null;
        }
        User manager = accessControl.loadUserInOrganization(managerId, organizationId);
        if (manager.getRole() != Role.MANAGER && manager.getRole() != Role.ORG_ADMIN) {
            throw new BusinessRuleException(
                    "A manager must hold the MANAGER or ORG_ADMIN role");
        }
        return manager;
    }

    private void applyActiveChange(User user, boolean active, UUID organizationId) {
        if (user.isActive() == active) {
            return;
        }
        if (!active) {
            guardLastOrgAdmin(user, Role.EMPLOYEE, organizationId);
            // End every live session immediately on deactivation.
            refreshTokenRepository.revokeAllForUser(user.getId());
            notificationService.notify(user, user.getOrganization(),
                    NotificationType.USER_DEACTIVATED,
                    "Account deactivated",
                    "Your account has been deactivated. Contact your administrator for details.",
                    user.getId());
            log.info("User {} deactivated; sessions revoked", user.getId());
        }
        user.setActive(active);
    }

    /** An organization must always retain at least one active ORG_ADMIN. */
    private void guardLastOrgAdmin(User user, Role newRole, UUID organizationId) {
        boolean losingAdmin = user.getRole() == Role.ORG_ADMIN && newRole != Role.ORG_ADMIN;
        if (!losingAdmin) {
            return;
        }
        long remainingAdmins = userRepository
                .findByOrganizationIdAndRoleIn(organizationId, List.of(Role.ORG_ADMIN))
                .stream()
                .filter(u -> !u.getId().equals(user.getId()))
                .count();
        if (remainingAdmins == 0) {
            throw new BusinessRuleException(
                    "This is the organization's only active admin; promote another user first");
        }
    }
}
