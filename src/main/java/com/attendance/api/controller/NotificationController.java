package com.attendance.api.controller;

import com.attendance.api.dto.common.MessageResponse;
import com.attendance.api.dto.common.PageResponse;
import com.attendance.api.dto.notification.NotificationResponse;
import com.attendance.api.dto.notification.UnreadCountResponse;
import com.attendance.api.service.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/notifications")
@Tag(name = "Notifications", description = "In-app notification inbox for the caller")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping
    @Operation(summary = "List the caller's notifications",
            description = """
                    Any authenticated role. A notification is always addressed to exactly one
                    user, so this endpoint is inherently self-scoped — there is no way to read
                    another user's inbox.
                    """)
    public PageResponse<NotificationResponse> list(
            @Parameter(description = "Return only unread notifications")
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return notificationService.listForCurrentUser(unreadOnly, pageable);
    }

    @GetMapping("/unread-count")
    @Operation(summary = "Unread notification count",
            description = "Any authenticated role. Drives the badge in the web and mobile apps.")
    public UnreadCountResponse unreadCount() {
        return new UnreadCountResponse(notificationService.unreadCountForCurrentUser());
    }

    @PutMapping("/{notificationId}/read")
    @Operation(summary = "Mark one notification as read", description = "Any authenticated role.")
    public NotificationResponse markRead(@PathVariable UUID notificationId) {
        return notificationService.markRead(notificationId);
    }

    @PutMapping("/read-all")
    @Operation(summary = "Mark every notification as read", description = "Any authenticated role.")
    public MessageResponse markAllRead() {
        int updated = notificationService.markAllRead();
        return new MessageResponse(updated + " notification(s) marked as read");
    }

    @DeleteMapping("/{notificationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete one notification", description = "Any authenticated role.")
    public void delete(@PathVariable UUID notificationId) {
        notificationService.delete(notificationId);
    }

    @DeleteMapping("/purge")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ORG_ADMIN')")
    @Operation(summary = "Purge notifications past the retention window",
            description = """
                    **SUPER_ADMIN** or **ORG_ADMIN**. Deletes notices older than
                    `app.notifications.retention-days` (default 90). Returns the number removed.
                    """)
    public MessageResponse purge() {
        int removed = notificationService.purgeOlderThanRetention();
        return new MessageResponse(removed + " notification(s) purged");
    }
}
