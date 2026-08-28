package com.attendance.api.service;

import com.attendance.api.domain.AttendanceRecord;
import com.attendance.api.domain.Organization;
import com.attendance.api.domain.enums.NotificationType;
import com.attendance.api.repository.AttendanceRecordRepository;
import com.attendance.api.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/** Background housekeeping: check-out reminders, retention sweeps, token cleanup. */
@Service
@RequiredArgsConstructor
@Slf4j
public class ScheduledJobsService {

    private final AttendanceRecordRepository attendanceRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final NotificationService notificationService;

    /**
     * Reminds anyone still checked in that their day is not closed out.
     *
     * <p>Runs hourly rather than once at a fixed hour because organizations sit in different
     * timezones: each run notifies only those orgs whose local time has just passed their
     * configured end of day.
     */
    @Scheduled(cron = "0 5 * * * *")
    @Transactional
    public void sendCheckOutReminders() {
        int notified = 0;

        for (AttendanceRecord record : openRecordsForRecentDays()) {
            Organization organization = record.getOrganization();
            LocalTime localNow = Instant.now()
                    .atZone(AttendanceService.zoneOf(organization))
                    .toLocalTime();

            // Fire in the hour immediately following the org's end of day.
            int endHour = organization.getWorkEndHour();
            if (localNow.getHour() != endHour) {
                continue;
            }

            notificationService.notify(record.getUser(), organization,
                    NotificationType.CHECK_OUT_REMINDER,
                    "You are still checked in",
                    String.format("Your check-in for %s has no check-out yet. "
                            + "Please check out to close the day.", record.getWorkDate()),
                    record.getId());
            notified++;
        }

        if (notified > 0) {
            log.info("Sent {} check-out reminder(s)", notified);
        }
    }

    /** Today and yesterday, so a late-evening shift in a western timezone is still covered. */
    private List<AttendanceRecord> openRecordsForRecentDays() {
        LocalDate today = LocalDate.now();
        List<AttendanceRecord> records =
                new java.util.ArrayList<>(attendanceRepository.findOpenByWorkDate(today));
        records.addAll(attendanceRepository.findOpenByWorkDate(today.minusDays(1)));
        return records;
    }

    /** Nightly retention sweep for the notification inbox. */
    @Scheduled(cron = "0 30 2 * * *")
    public void purgeOldNotifications() {
        notificationService.purgeOlderThanRetention();
    }

    /** Nightly cleanup of refresh tokens that can no longer be redeemed. */
    @Scheduled(cron = "0 45 2 * * *")
    @Transactional
    public void purgeExpiredRefreshTokens() {
        int removed = refreshTokenRepository.deleteExpired(Instant.now());
        if (removed > 0) {
            log.info("Deleted {} expired refresh token(s)", removed);
        }
    }
}
