package com.attendance.api.service;

import com.attendance.api.domain.Organization;
import com.attendance.api.domain.RefreshToken;
import com.attendance.api.domain.User;
import com.attendance.api.domain.enums.Role;
import com.attendance.api.dto.auth.*;
import com.attendance.api.dto.user.ChangePasswordRequest;
import com.attendance.api.dto.user.UserResponse;
import com.attendance.api.exception.BusinessRuleException;
import com.attendance.api.exception.ConflictException;
import com.attendance.api.exception.Require;
import com.attendance.api.repository.OrganizationRepository;
import com.attendance.api.repository.RefreshTokenRepository;
import com.attendance.api.repository.UserRepository;
import com.attendance.api.security.JwtTokenProvider;
import com.attendance.api.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Login, token refresh, logout, tenant signup and self-service password change. */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final UserRepository userRepository;
    private final OrganizationRepository organizationRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final LeaveBalanceService leaveBalanceService;
    private final JwtTokenProvider tokenProvider;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public AuthResponse login(LoginRequest request) {
        User user = locateUserForLogin(request.email(), request.tenantKey());

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            log.warn("Failed login for {} (bad password)", request.email());
            throw new BadCredentialsException("Invalid credentials");
        }
        if (!user.isActive()) {
            log.warn("Failed login for {} (account deactivated)", request.email());
            throw new DisabledException("Account deactivated");
        }
        Organization userOrganization = user.getOrganization();
        if (userOrganization != null && !userOrganization.isActive()) {
            log.warn("Failed login for {} (organization deactivated)", request.email());
            throw new DisabledException("Organization deactivated");
        }

        user.setLastLoginAt(Instant.now());
        userRepository.save(user);
        log.info("Login succeeded for {} (role={}, org={})",
                user.getEmail(), user.getRole(), user.resolveOrganizationId());

        return issueTokens(user);
    }

    /**
     * Resolves the login identity. Email is unique per tenant, so the same address can
     * exist in several organizations; a tenant key disambiguates. When none is supplied
     * we accept the login only if exactly one account matches.
     */
    private User locateUserForLogin(String email, String tenantKey) {
        if (StringUtils.hasText(tenantKey)) {
            Organization org = Require.present(
                    organizationRepository.findByTenantKeyIgnoreCase(tenantKey.trim()),
                    () -> new BadCredentialsException("Invalid credentials"));
            return Require.present(
                    userRepository.findByEmailAndOrganizationId(email, org.getId()),
                    () -> new BadCredentialsException("Invalid credentials"));
        }

        List<User> matches = userRepository.findAllByEmail(email);
        if (matches.isEmpty()) {
            throw new BadCredentialsException("Invalid credentials");
        }
        if (matches.size() > 1) {
            throw new BusinessRuleException(
                    "This email exists in more than one organization; supply tenantKey to sign in");
        }
        return matches.get(0);
    }

    @Transactional
    public AuthResponse refresh(RefreshTokenRequest request) {
        String hash = tokenProvider.hash(request.refreshToken());
        RefreshToken stored = Require.present(refreshTokenRepository.findByTokenHash(hash),
                () -> new BadCredentialsException("Invalid refresh token"));

        if (!stored.isUsable()) {
            throw new BadCredentialsException("Refresh token is expired or revoked");
        }

        User user = stored.getUser();
        if (!user.isActive()) {
            throw new DisabledException("Account deactivated");
        }

        // Rotate: the presented token is retired as part of issuing its replacement.
        stored.setRevoked(true);
        refreshTokenRepository.save(stored);

        return issueTokens(user);
    }

    @Transactional
    public void logout(RefreshTokenRequest request) {
        // Revoke every live token for the user so all their sessions end, not just this one.
        refreshTokenRepository.findByTokenHash(tokenProvider.hash(request.refreshToken()))
                .ifPresent(token -> {
                    int revoked = refreshTokenRepository.revokeAllForUser(token.getUser().getId());
                    log.info("Logout revoked {} refresh token(s) for user {}",
                            revoked, token.getUser().getId());
                });
    }

    /** Self-service tenant signup: creates the organization plus its first ORG_ADMIN. */
    @Transactional
    public AuthResponse registerOrganization(RegisterOrganizationRequest request) {
        String tenantKey = request.tenantKey().toLowerCase().trim();

        if (organizationRepository.existsByTenantKeyIgnoreCase(tenantKey)) {
            throw new ConflictException("Tenant key already taken: " + tenantKey);
        }
        if (organizationRepository.existsByNameIgnoreCase(request.organizationName().trim())) {
            throw new ConflictException("Organization name already taken: " + request.organizationName());
        }

        Organization organization = organizationRepository.save(Organization.builder()
                .name(request.organizationName().trim())
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

        log.info("Registered organization {} (tenantKey={}) with admin {}",
                organization.getName(), tenantKey, admin.getEmail());

        return issueTokens(admin);
    }

    @Transactional
    public void changePassword(ChangePasswordRequest request) {
        UUID userId = SecurityUtils.currentUserId();
        User user = Require.found(userRepository.findById(userId), "User", userId);

        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new BadCredentialsException("Current password is incorrect");
        }
        if (passwordEncoder.matches(request.newPassword(), user.getPasswordHash())) {
            throw new BusinessRuleException("New password must differ from the current one");
        }

        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);

        // Force re-authentication everywhere after a credential change.
        refreshTokenRepository.revokeAllForUser(userId);
        log.info("Password changed for user {}", userId);
    }

    @Transactional(readOnly = true)
    public UserResponse currentUser() {
        UUID userId = SecurityUtils.currentUserId();
        return Require.found(
                userRepository.findById(userId) .map(UserResponse::from),
                "User", userId);
    }

    private AuthResponse issueTokens(User user) {
        String accessToken = tokenProvider.createAccessToken(user);
        String rawRefresh = tokenProvider.createRefreshToken();

        refreshTokenRepository.save(RefreshToken.builder()
                .user(user)
                .tokenHash(tokenProvider.hash(rawRefresh))
                .expiresAt(tokenProvider.refreshTokenExpiry())
                .revoked(false)
                .build());

        return AuthResponse.of(accessToken, rawRefresh,
                tokenProvider.getAccessTokenSeconds(), UserResponse.from(user));
    }
}
