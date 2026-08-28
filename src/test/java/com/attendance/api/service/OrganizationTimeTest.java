package com.attendance.api.service;

import com.attendance.api.domain.Organization;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/** Lateness must be judged in the organization's timezone, not the server's. */
class OrganizationTimeTest {

    private static Organization org(String timezone, int startHour) {
        return Organization.builder()
                .name("Test")
                .tenantKey("test")
                .timezone(timezone)
                .workStartHour(startHour)
                .workEndHour(17)
                .build();
    }

    @Test
    @DisplayName("08:30 local is on time against a 09:00 start")
    void onTime() {
        // 03:30 UTC is 08:30 in Asia/Karachi (UTC+5).
        Instant checkIn = Instant.parse("2026-08-27T03:30:00Z");
        assertThat(AttendanceService.isLate(checkIn, org("Asia/Karachi", 9))).isFalse();
    }

    @Test
    @DisplayName("09:30 local is late against a 09:00 start")
    void late() {
        Instant checkIn = Instant.parse("2026-08-27T04:30:00Z");
        Organization organization = org("Asia/Karachi", 9);
        assertThat(AttendanceService.isLate(checkIn, organization)).isTrue();
        assertThat(AttendanceService.minutesLate(checkIn, organization)).isEqualTo(30);
    }

    @Test
    @DisplayName("the same instant is late in one timezone and on time in another")
    void timezoneDecidesLateness() {
        Instant checkIn = Instant.parse("2026-08-27T04:30:00Z");
        // 09:30 in Karachi (UTC+5) -> late; 04:30 in UTC -> before a 09:00 start.
        assertThat(AttendanceService.isLate(checkIn, org("Asia/Karachi", 9))).isTrue();
        assertThat(AttendanceService.isLate(checkIn, org("UTC", 9))).isFalse();
    }

    @Test
    @DisplayName("an unparseable timezone falls back to UTC instead of failing")
    void invalidTimezoneFallsBack() {
        Organization organization = org("Not/AZone", 9);
        assertThat(AttendanceService.zoneOf(organization).getRules())
                .isEqualTo(ZoneId.of("UTC").getRules());
        assertThat(AttendanceService.isLate(Instant.parse("2026-08-27T04:30:00Z"), organization))
                .isFalse();
    }

    @Test
    @DisplayName("minutesLate is zero for an on-time arrival")
    void noNegativeMinutes() {
        Instant checkIn = Instant.parse("2026-08-27T02:00:00Z");
        assertThat(AttendanceService.minutesLate(checkIn, org("Asia/Karachi", 9))).isZero();
    }
}
