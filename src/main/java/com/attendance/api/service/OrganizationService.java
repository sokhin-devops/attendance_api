package com.attendance.api.service;

import com.attendance.api.domain.Organization;
import com.attendance.api.domain.User;
import com.attendance.api.domain.enums.Role;
import com.attendance.api.dto.common.PageResponse;
import com.attendance.api.dto.organization.CreateOrganizationRequest;
import com.attendance.api.dto.organization.OrganizationResponse;
import com.attendance.api.dto.organization.UpdateOrganizationRequest;
import com.attendance.api.exception.ConflictException;
import com.attendance.api.exception.Require;
import com.attendance.api.repository.OrganizationRepository;
import com.attendance.api.repository.QueryParams;
import com.attendance.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.UUID;
import org.springframework.lang.NonNull;

/** Tenant provisioning and settings. Listing and creation are super-admin only. */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrganizationService {

    private final OrganizationRepository organizationRepository;
    private final UserRepository userRepository;
    private final LeaveBalanceService leaveBalanceService;
    private final AccessControlService accessControl;
    private final PasswordEncoder passwordEncoder;

    @Transactional(readOnly = true)
    public PageResponse<OrganizationResponse> list(String search, Pageable pageable) {
        return PageResponse.of(
                organizationRepository.search(QueryParams.likePattern(search), pageable),
                org -> OrganizationResponse.from(
                        org, userRepository.countActiveByOrganizationId(org.getId())));
    }

    @Transactional(readOnly = true)
    public OrganizationResponse get(UUID organizationId) {
        UUID orgId = accessControl.resolveOrganization(organizationId);
        Organization org = requireOrganization(orgId);
        return OrganizationResponse.from(org, userRepository.countActiveByOrganizationId(orgId));
    }

    /** Super-admin provisioning of a tenant plus its first org admin. */
    @Transactional
    public OrganizationResponse create(CreateOrganizationRequest request) {
        String tenantKey = request.tenantKey().toLowerCase().trim();

        if (organizationRepository.existsByTenantKeyIgnoreCase(tenantKey)) {
            throw new ConflictException("Tenant key already taken: " + tenantKey);
        }
        if (organizationRepository.existsByNameIgnoreCase(request.name().trim())) {
            throw new ConflictException("Organization name already taken: " + request.name());
        }

        Organization organization = organizationRepository.save(Organization.builder()
                .name(request.name().trim())
                .tenantKey(tenantKey)
                .timezone(StringUtils.hasText(request.timezone()) ? request.timezone().trim() : "UTC")
                .workStartHour(request.workStartHour() == null ? 9 : request.workStartHour())
                .workEndHour(request.workEndHour() == null ? 17 : request.workEndHour())
                .allowManualCheckIn(false)
                .active(true)
                .build());

        User admin = userRepository.save(User.builder()
                .organization(organization)
                .email(request.adminEmail().trim().toLowerCase())
                .passwordHash(passwordEncoder.encode(request.adminPassword()))
                .firstName(request.adminFirstName().trim())
                .lastName(request.adminLastName().trim())
                .role(Role.ORG_ADMIN)
                .active(true)
                .build());

        leaveBalanceService.seedDefaultBalances(admin);

        log.info("Super admin provisioned organization {} (tenantKey={}) with admin {}",
                organization.getName(), tenantKey, admin.getEmail());

        return OrganizationResponse.from(organization, 1L);
    }

    @Transactional
    public OrganizationResponse update(UUID organizationId, UpdateOrganizationRequest request) {
        UUID orgId = accessControl.resolveOrganization(organizationId);
        Organization org = requireOrganization(orgId);

        if (StringUtils.hasText(request.name())) {
            String newName = request.name().trim();
            if (!newName.equalsIgnoreCase(org.getName())
                    && organizationRepository.existsByNameIgnoreCase(newName)) {
                throw new ConflictException("Organization name already taken: " + newName);
            }
            org.setName(newName);
        }
        if (StringUtils.hasText(request.timezone())) {
            org.setTimezone(request.timezone().trim());
        }
        if (request.workStartHour() != null) {
            org.setWorkStartHour(request.workStartHour());
        }
        if (request.workEndHour() != null) {
            org.setWorkEndHour(request.workEndHour());
        }
        if (request.allowManualCheckIn() != null) {
            org.setAllowManualCheckIn(request.allowManualCheckIn());
        }
        if (request.active() != null) {
            org.setActive(request.active());
        }

        Organization saved = organizationRepository.save(org);
        log.info("Organization {} updated", saved.getId());
        return OrganizationResponse.from(saved, userRepository.countActiveByOrganizationId(orgId));
    }

    /** Deactivation rather than deletion: attendance history must survive. */
    @Transactional
    public void deactivate(@NonNull UUID organizationId) {
        Organization org = requireOrganization(organizationId);
        org.setActive(false);
        organizationRepository.save(org);
        log.info("Organization {} deactivated", organizationId);
    }

    @Transactional(readOnly = true)
    @NonNull
    public Organization requireOrganization(@NonNull UUID organizationId) {
        return Require.found(
                organizationRepository.findById(organizationId),
                "Organization", organizationId);
    }
}
