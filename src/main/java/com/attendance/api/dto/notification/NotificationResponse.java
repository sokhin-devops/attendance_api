package com.attendance.api.dto.notification;

import com.attendance.api.domain.Notification;
import com.attendance.api.domain.enums.NotificationType;

import java.time.Instant;
import java.util.UUID;

public record NotificationResponse(
        UUID id,
        String title,
        String message,
        NotificationType type,
        UUID relatedEntityId,
        boolean read,
        Instant createdAt
) {
    public static NotificationResponse from(Notification n) {
        return new NotificationResponse(
                n.getId(), n.getTitle(), n.getMessage(), n.getType(),
                n.getRelatedEntityId(), n.isRead(), n.getCreatedAt());
    }
}
