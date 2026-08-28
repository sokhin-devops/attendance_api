package com.attendance.api.service;

import com.attendance.api.config.AppProperties;
import com.attendance.api.domain.Notification;
import com.attendance.api.domain.Organization;
import com.attendance.api.domain.User;
import com.attendance.api.domain.enums.NotificationType;
import com.attendance.api.dto.common.PageResponse;
import com.attendance.api.dto.notification.NotificationResponse;
import com.attendance.api.exception.Require;
import com.attendance.api.repository.NotificationRepository;
import com.attendance.api.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Creates in-app notifications and hands them to {@link PushNotificationService}
 * for FCM delivery. Every write is called from inside the originating transaction so a
 * failed business operation never leaves an orphaned notice behind.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final PushNotificationService pushNotificationService;
    private final AppProperties appProperties;

    @Transactional
    public Notification notify(User recipient, Organization organization, NotificationType type,
                               String title, String message, UUID relatedEntityId) {
        Notification notification = notificationRepository.save(Notification.builder()
                .user(recipient)
                .organization(organization)
                .type(type)
                .title(title)
                .message(message)
                .relatedEntityId(relatedEntityId)
                .read(false)
                .build());

        log.debug("Notification {} created for user {} ({})", type, recipient.getId(), title);
        pushNotificationService.send(recipient, title, message, type, relatedEntityId);
        return notification;
    }

    /** Fan-out helper for notices that go to several approvers at once. */
    @Transactional
    public void notifyAll(List<User> recipients, Organization organization, NotificationType type,
                          String title, String message, UUID relatedEntityId) {
        recipients.forEach(r -> notify(r, organization, type, title, message, relatedEntityId));
    }

    @Transactional(readOnly = true)
    public PageResponse<NotificationResponse> listForCurrentUser(boolean unreadOnly, Pageable pageable) {
        UUID userId = SecurityUtils.currentUserId();
        return PageResponse.of(
                notificationRepository.findForUser(userId, unreadOnly, pageable),
                NotificationResponse::from);
    }

    @Transactional(readOnly = true)
    public long unreadCountForCurrentUser() {
        return notificationRepository.countByUserIdAndReadFalse(SecurityUtils.currentUserId());
    }

    @Transactional
    public NotificationResponse markRead(UUID notificationId) {
        UUID userId = SecurityUtils.currentUserId();
        Notification notification = Require.found(
                notificationRepository.findByIdAndUserId(notificationId, userId),
                "Notification", notificationId);
        notification.setRead(true);
        return NotificationResponse.from(notificationRepository.save(notification));
    }

    @Transactional
    public int markAllRead() {
        return notificationRepository.markAllReadForUser(SecurityUtils.currentUserId());
    }

    @Transactional
    public void delete(UUID notificationId) {
        UUID userId = SecurityUtils.currentUserId();
        Notification notification = Require.found(
                notificationRepository.findByIdAndUserId(notificationId, userId),
                "Notification", notificationId);
        notificationRepository.delete(notification);
    }

    /** Retention sweep driven by {@code app.notifications.retention-days}. */
    @Transactional
    public int purgeOlderThanRetention() {
        Instant cutoff = Instant.now().minus(
                appProperties.getNotifications().getRetentionDays(), ChronoUnit.DAYS);
        int removed = notificationRepository.deleteOlderThan(cutoff);
        if (removed > 0) {
            log.info("Purged {} notifications older than {}", removed, cutoff);
        }
        return removed;
    }
}
