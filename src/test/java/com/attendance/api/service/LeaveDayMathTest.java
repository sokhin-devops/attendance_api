package com.attendance.api.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class LeaveDayMathTest {

    @Test
    @DisplayName("a single-day request counts as one day")
    void singleDay() {
        LocalDate day = LocalDate.of(2026, 9, 1);
        assertThat(LeaveService.inclusiveDays(day, day)).isEqualTo(1);
    }

    @Test
    @DisplayName("Mon to Wed counts as three days, not two")
    void inclusiveOfBothEnds() {
        assertThat(LeaveService.inclusiveDays(
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 3))).isEqualTo(3);
    }

    @Test
    @DisplayName("a range spanning a month boundary is counted correctly")
    void acrossMonthBoundary() {
        assertThat(LeaveService.inclusiveDays(
                LocalDate.of(2026, 8, 30), LocalDate.of(2026, 9, 2))).isEqualTo(4);
    }

    @Test
    @DisplayName("a leap day is included")
    void leapYear() {
        assertThat(LeaveService.inclusiveDays(
                LocalDate.of(2028, 2, 27), LocalDate.of(2028, 3, 1))).isEqualTo(4);
    }
}
