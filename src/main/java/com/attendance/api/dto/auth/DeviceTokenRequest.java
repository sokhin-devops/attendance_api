package com.attendance.api.dto.auth;

import com.attendance.api.domain.enums.DevicePlatform;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Registers an FCM token so push notifications can reach this device. */
public record DeviceTokenRequest(
        @NotBlank String fcmToken,
        @NotNull DevicePlatform platform
) {
}
