package com.attendance.api.service;

import com.attendance.api.config.AppProperties;
import com.attendance.api.domain.DeviceToken;
import com.attendance.api.domain.User;
import com.attendance.api.domain.enums.DevicePlatform;
import com.attendance.api.domain.enums.NotificationType;
import com.attendance.api.repository.DeviceTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Delivers push notifications to a user's registered devices.
 *
 * <p>Dispatch is deliberately behind {@code app.fcm.enabled}. With FCM disabled the
 * payload that would have been sent is logged instead, so the whole notification path
 * is exercised end-to-end without requiring Firebase credentials in development. Wiring
 * the real transport means replacing the body of {@link #dispatch} with a
 * {@code FirebaseMessaging.getInstance().sendEachForMulticast(...)} call; nothing else
 * in the codebase needs to change.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PushNotificationService {

    private final DeviceTokenRepository deviceTokenRepository;
    private final AppProperties appProperties;

    @Transactional(readOnly = true)
    public void send(User recipient, String title, String body,
                     NotificationType type, UUID relatedEntityId) {
        List<DeviceToken> devices = deviceTokenRepository.findByUserId(recipient.getId());
        if (devices.isEmpty()) {
            log.debug("No registered devices for user {}; skipping push", recipient.getId());
            return;
        }
        dispatch(devices, title, body, type, relatedEntityId);
    }

    private void dispatch(List<DeviceToken> devices, String title, String body,
                          NotificationType type, UUID relatedEntityId) {
        if (!appProperties.getFcm().isEnabled()) {
            log.info("[FCM disabled] would push to {} device(s): type={} title=\"{}\" body=\"{}\" related={}",
                    devices.size(), type, title, body, relatedEntityId);
            return;
        }
        // Real FCM multicast goes here once Firebase credentials are configured.
        log.warn("FCM is enabled but no transport is configured; dropping push type={}", type);
    }

    /** Registers or re-points an FCM token to the given user. */
    @Transactional
    public void registerDevice(User user, String fcmToken, DevicePlatform platform) {
        deviceTokenRepository.findByFcmToken(fcmToken).ifPresentOrElse(
                existing -> {
                    // A device can change hands between users on shared hardware.
                    existing.setUser(user);
                    existing.setPlatform(platform);
                    deviceTokenRepository.save(existing);
                },
                () -> deviceTokenRepository.save(DeviceToken.builder()
                        .user(user)
                        .fcmToken(fcmToken)
                        .platform(platform)
                        .build()));
        log.debug("Registered {} device token for user {}", platform, user.getId());
    }

    @Transactional
    public void unregisterDevice(String fcmToken) {
        deviceTokenRepository.deleteByFcmToken(fcmToken);
    }
}
