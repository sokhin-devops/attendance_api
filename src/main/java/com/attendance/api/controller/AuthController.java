package com.attendance.api.controller;

import com.attendance.api.domain.User;
import com.attendance.api.dto.auth.*;
import com.attendance.api.dto.common.MessageResponse;
import com.attendance.api.dto.user.ChangePasswordRequest;
import com.attendance.api.dto.user.UserResponse;
import com.attendance.api.exception.Require;
import com.attendance.api.repository.UserRepository;
import com.attendance.api.security.SecurityUtils;
import com.attendance.api.service.AuthService;
import com.attendance.api.service.PushNotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "Login, token refresh, signup and profile")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final PushNotificationService pushNotificationService;
    private final UserRepository userRepository;

    @PostMapping("/login")
    @SecurityRequirements
    @Operation(summary = "Log in with email and password",
            description = """
                    Public. Returns an access token (15 min) and a refresh token (7 days).

                    Email is unique **per organization**, so if the same address exists in
                    several tenants you must also send `tenantKey`. Platform super admins
                    log in with email alone.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Authenticated"),
            @ApiResponse(responseCode = "400", description = "Email is ambiguous across tenants"),
            @ApiResponse(responseCode = "401", description = "Invalid email or password"),
            @ApiResponse(responseCode = "403", description = "Account or organization deactivated")
    })
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @PostMapping("/refresh")
    @SecurityRequirements
    @Operation(summary = "Exchange a refresh token for a new token pair",
            description = "Public. The presented refresh token is rotated out and replaced.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "New token pair issued"),
            @ApiResponse(responseCode = "401", description = "Refresh token invalid, expired or revoked")
    })
    public AuthResponse refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return authService.refresh(request);
    }

    @PostMapping("/register-organization")
    @SecurityRequirements
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Self-service tenant signup",
            description = """
                    Public. Creates an organization together with its first `ORG_ADMIN`
                    and returns tokens for that admin, so signup flows straight into the app.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Organization and admin created"),
            @ApiResponse(responseCode = "409", description = "Organization name or tenant key taken")
    })
    public AuthResponse registerOrganization(@Valid @RequestBody RegisterOrganizationRequest request) {
        return authService.registerOrganization(request);
    }

    @PostMapping("/logout")
    @Operation(summary = "Revoke all refresh tokens for the caller",
            description = "Any authenticated role. Ends every active session for the user.",
            security = @SecurityRequirement(name = "bearerAuth"))
    public MessageResponse logout(@Valid @RequestBody RefreshTokenRequest request) {
        authService.logout(request);
        return new MessageResponse("Logged out");
    }

    @GetMapping("/me")
    @Operation(summary = "Current user profile",
            description = "Any authenticated role. Resolves the caller from the bearer token.")
    public UserResponse me() {
        return authService.currentUser();
    }

    @PostMapping("/change-password")
    @Operation(summary = "Change your own password",
            description = """
                    Any authenticated role. Requires the current password. On success every
                    refresh token for the account is revoked, forcing a fresh login.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Password changed"),
            @ApiResponse(responseCode = "401", description = "Current password is incorrect")
    })
    public MessageResponse changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        authService.changePassword(request);
        return new MessageResponse("Password changed. Please sign in again.");
    }

    @PostMapping("/devices")
    @Operation(summary = "Register this device for push notifications",
            description = "Any authenticated role. Call after login on mobile with the FCM token.")
    public ResponseEntity<MessageResponse> registerDevice(@Valid @RequestBody DeviceTokenRequest request) {
        UUID userId = SecurityUtils.currentUserId();
        User user = Require.found(userRepository.findById(userId), "User", userId);
        pushNotificationService.registerDevice(user, request.fcmToken(), request.platform());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new MessageResponse("Device registered for push notifications"));
    }

    @DeleteMapping("/devices")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Unregister a device from push notifications",
            description = "Any authenticated role. Call on logout from mobile.")
    public void unregisterDevice(@RequestParam String fcmToken) {
        pushNotificationService.unregisterDevice(fcmToken);
    }
}
