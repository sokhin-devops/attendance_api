package com.attendance.api.integration;

import com.attendance.api.domain.*;
import com.attendance.api.domain.enums.LeaveStatus;
import com.attendance.api.domain.enums.LeaveType;
import com.attendance.api.domain.enums.NotificationType;
import com.attendance.api.domain.enums.Role;
import com.attendance.api.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.lang.NonNull;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AttendanceIntegrationTest extends IntegrationTestBase {

    /** Head office reference point; the fixtures below sit at known offsets from it. */
    private static final double OFFICE_LAT = 24.8607;
    private static final double OFFICE_LON = 67.0011;

    @NonNull
    private Organization org = Organization.builder().name("unset").tenantKey("unset").build();

    @NonNull
    private Location office = Location.builder().name("unset").latitude(0.0).longitude(0.0).build();
    private User admin;
    private User manager;
    private User employee;
    private User otherEmployee;

    @BeforeEach
    void setUpTenant() {
        org = givenOrganization("Acme", "acme", "UTC", 9, false);
        office = givenLocation(org, "Head Office", OFFICE_LAT, OFFICE_LON, 150);
        admin = givenUser(org, "admin@acme.test", ORG_ADMIN_PASSWORD, Role.ORG_ADMIN, null);
        manager = givenUser(org, "mgr@acme.test", MANAGER_PASSWORD, Role.MANAGER, null);
        employee = givenUser(org, "emp@acme.test", EMPLOYEE_PASSWORD, Role.EMPLOYEE, manager);
        otherEmployee = givenUser(org, "emp2@acme.test", EMPLOYEE_PASSWORD, Role.EMPLOYEE, null);
        assignLocation(employee, office);
    }

    @NonNull
    private String checkInPayload(double lat, double lon, String manualReason) {
        return manualReason == null
                ? json("locationId", office.getId(), "latitude", lat, "longitude", lon,
                       "gpsAccuracyMeters", 8.0)
                : json("locationId", office.getId(), "latitude", lat, "longitude", lon,
                       "gpsAccuracyMeters", 8.0, "manualReason", manualReason);
    }

    @Test
    @DisplayName("check-in inside the geofence is accepted and recorded")
    void checkInInsideGeofence() throws Exception {
        String token = tokenFor(employee, EMPLOYEE_PASSWORD);

        mockMvc.perform(authed(post("/api/v1/attendance/check-in"), token)
                        // ~15 m from the office
                        .content(checkInPayload(OFFICE_LAT + 0.00013, OFFICE_LON, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(employee.getId().toString()))
                .andExpect(jsonPath("$.locationName").value("Head Office"))
                .andExpect(jsonPath("$.manualOverride").value(false))
                .andExpect(jsonPath("$.checkOutTime").doesNotExist());

        assertThat(attendanceRepository.findByUserIdAndWorkDate(
                employee.getId(), LocalDate.now(ZoneOffset.UTC))).isPresent();
    }

    @Test
    @DisplayName("check-in outside the geofence is rejected with the distance and radius")
    void checkInOutsideGeofenceRejected() throws Exception {
        String token = tokenFor(employee, EMPLOYEE_PASSWORD);

        mockMvc.perform(authed(post("/api/v1/attendance/check-in"), token)
                        // Lahore: ~1030 km away
                        .content(checkInPayload(31.5204, 74.3587, null)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Outside Geofence"))
                .andExpect(jsonPath("$.fieldErrors.allowedRadiusMeters").value("150"))
                .andExpect(jsonPath("$.fieldErrors.distanceMeters").exists());

        assertThat(attendanceRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("just outside the radius is rejected, just inside is accepted")
    void geofenceBoundary() throws Exception {
        String token = tokenFor(employee, EMPLOYEE_PASSWORD);

        // ~222 m north: outside the 150 m fence.
        mockMvc.perform(authed(post("/api/v1/attendance/check-in"), token)
                        .content(checkInPayload(OFFICE_LAT + 0.002, OFFICE_LON, null)))
                .andExpect(status().isForbidden());

        // ~111 m north: inside.
        mockMvc.perform(authed(post("/api/v1/attendance/check-in"), token)
                        .content(checkInPayload(OFFICE_LAT + 0.001, OFFICE_LON, null)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a manual reason is ignored unless the organization enables manual check-in")
    void manualReasonRequiresOrgSetting() throws Exception {
        String token = tokenFor(employee, EMPLOYEE_PASSWORD);

        mockMvc.perform(authed(post("/api/v1/attendance/check-in"), token)
                        .content(checkInPayload(31.5204, 74.3587, "Working from the Lahore site")))
                .andExpect(status().isForbidden());

        org.setAllowManualCheckIn(true);
        organizationRepository.save(org);

        mockMvc.perform(authed(post("/api/v1/attendance/check-in"), token)
                        .content(checkInPayload(31.5204, 74.3587, "Working from the Lahore site")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.manualOverride").value(true))
                .andExpect(jsonPath("$.overrideReason").value("Working from the Lahore site"));
    }

    @Test
    @DisplayName("manual check-in still requires a written reason even when permitted")
    void manualCheckInNeedsReason() throws Exception {
        org.setAllowManualCheckIn(true);
        organizationRepository.save(org);
        String token = tokenFor(employee, EMPLOYEE_PASSWORD);

        mockMvc.perform(authed(post("/api/v1/attendance/check-in"), token)
                        .content(checkInPayload(31.5204, 74.3587, null)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a second check-in on the same day is a conflict")
    void duplicateCheckIn() throws Exception {
        String token = tokenFor(employee, EMPLOYEE_PASSWORD);
        String payload = checkInPayload(OFFICE_LAT, OFFICE_LON, null);

        mockMvc.perform(authed(post("/api/v1/attendance/check-in"), token).content(payload))
                .andExpect(status().isOk());
        mockMvc.perform(authed(post("/api/v1/attendance/check-in"), token).content(payload))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("an employee cannot check in at a location they are not assigned to")
    void unassignedLocationRejected() throws Exception {
        String token = tokenFor(otherEmployee, EMPLOYEE_PASSWORD);

        mockMvc.perform(authed(post("/api/v1/attendance/check-in"), token)
                        .content(checkInPayload(OFFICE_LAT, OFFICE_LON, null)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(
                        contains("not assigned")));
    }

    @Test
    @DisplayName("an inactive location cannot be used for check-in")
    void inactiveLocationRejected() throws Exception {
        office.setActive(false);
        locationRepository.save(office);
        String token = tokenFor(employee, EMPLOYEE_PASSWORD);

        mockMvc.perform(authed(post("/api/v1/attendance/check-in"), token)
                        .content(checkInPayload(OFFICE_LAT, OFFICE_LON, null)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("check-out closes the day and reports the hours worked")
    void checkOut() throws Exception {
        String token = tokenFor(employee, EMPLOYEE_PASSWORD);
        mockMvc.perform(authed(post("/api/v1/attendance/check-in"), token)
                        .content(checkInPayload(OFFICE_LAT, OFFICE_LON, null)))
                .andExpect(status().isOk());

        mockMvc.perform(authed(post("/api/v1/attendance/check-out"), token)
                        .content(json("latitude", OFFICE_LAT, "longitude", OFFICE_LON)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkOutTime").exists())
                .andExpect(jsonPath("$.workedHours").exists());

        mockMvc.perform(authed(post("/api/v1/attendance/check-out"), token)
                        .content(json("latitude", OFFICE_LAT, "longitude", OFFICE_LON)))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("check-out without a check-in is a bad request, not a crash")
    void checkOutWithoutCheckIn() throws Exception {
        String token = tokenFor(employee, EMPLOYEE_PASSWORD);

        mockMvc.perform(authed(post("/api/v1/attendance/check-out"), token).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a late check-in is flagged and notifies both employee and manager")
    void lateCheckInNotifies() throws Exception {
        // Start the working day at midnight so any check-in now counts as late.
        org.setWorkStartHour(0);
        organizationRepository.save(org);
        String token = tokenFor(employee, EMPLOYEE_PASSWORD);

        mockMvc.perform(authed(post("/api/v1/attendance/check-in"), token)
                        .content(checkInPayload(OFFICE_LAT, OFFICE_LON, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.late").value(true));

        assertThat(notificationRepository.findAll())
                .filteredOn(n -> n.getType() == NotificationType.LATE_CHECK_IN)
                .extracting(n -> n.getUser().getId())
                .containsExactlyInAnyOrder(employee.getId(), manager.getId());
    }

    @Test
    @DisplayName("check-in is refused on a day the employee is on approved leave")
    void checkInBlockedOnApprovedLeave() throws Exception {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        leaveRequestRepository.save(LeaveRequest.builder()
                .organization(org)
                .employee(employee)
                .leaveType(LeaveType.ANNUAL)
                .fromDate(today)
                .toDate(today)
                .daysRequested(1)
                .status(LeaveStatus.APPROVED)
                .requestedBy(employee)
                .approvedBy(admin)
                .build());

        String token = tokenFor(employee, EMPLOYEE_PASSWORD);
        mockMvc.perform(authed(post("/api/v1/attendance/check-in"), token)
                        .content(checkInPayload(OFFICE_LAT, OFFICE_LON, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        contains("approved leave")));
    }

    @Test
    @DisplayName("the status endpoint reports today's state and assigned locations in one call")
    void statusEndpoint() throws Exception {
        String token = tokenFor(employee, EMPLOYEE_PASSWORD);

        mockMvc.perform(authed(get("/api/v1/attendance/status"), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkedIn").value(false))
                .andExpect(jsonPath("$.checkedOut").value(false))
                .andExpect(jsonPath("$.onApprovedLeave").value(false))
                .andExpect(jsonPath("$.manualCheckInAllowed").value(false))
                .andExpect(jsonPath("$.assignedLocations.length()").value(1));

        mockMvc.perform(authed(post("/api/v1/attendance/check-in"), token)
                        .content(checkInPayload(OFFICE_LAT, OFFICE_LON, null)))
                .andExpect(status().isOk());

        mockMvc.perform(authed(get("/api/v1/attendance/status"), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkedIn").value(true))
                .andExpect(jsonPath("$.todayRecord").exists());
    }

    @Test
    @DisplayName("an employee sees only their own history; a manager sees their team")
    void historyVisibility() throws Exception {
        String employeeToken = tokenFor(employee, EMPLOYEE_PASSWORD);
        String managerToken = tokenFor(manager, MANAGER_PASSWORD);
        String adminToken = tokenFor(admin, ORG_ADMIN_PASSWORD);

        mockMvc.perform(authed(post("/api/v1/attendance/check-in"), employeeToken)
                        .content(checkInPayload(OFFICE_LAT, OFFICE_LON, null)))
                .andExpect(status().isOk());

        mockMvc.perform(authed(get("/api/v1/attendance/me"), employeeToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));

        // Reaching for somebody else's records is refused.
        mockMvc.perform(authed(get("/api/v1/attendance")
                        .param("userId", otherEmployee.getId().toString()), employeeToken))
                .andExpect(status().isForbidden());

        // The manager may read a direct report but not an unrelated employee.
        mockMvc.perform(authed(get("/api/v1/attendance")
                        .param("userId", employee.getId().toString()), managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
        mockMvc.perform(authed(get("/api/v1/attendance")
                        .param("userId", otherEmployee.getId().toString()), managerToken))
                .andExpect(status().isForbidden());

        // The org admin sees the whole organization without naming a user.
        mockMvc.perform(authed(get("/api/v1/attendance"), adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @DisplayName("history filters by date range and rejects an inverted range")
    void historyDateFilters() throws Exception {
        String token = tokenFor(employee, EMPLOYEE_PASSWORD);
        mockMvc.perform(authed(post("/api/v1/attendance/check-in"), token)
                        .content(checkInPayload(OFFICE_LAT, OFFICE_LON, null)))
                .andExpect(status().isOk());

        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        mockMvc.perform(authed(get("/api/v1/attendance/me")
                        .param("fromDate", today.toString())
                        .param("toDate", today.toString()), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));

        mockMvc.perform(authed(get("/api/v1/attendance/me")
                        .param("fromDate", today.plusDays(5).toString())
                        .param("toDate", today.plusDays(10).toString()), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        mockMvc.perform(authed(get("/api/v1/attendance/me")
                        .param("fromDate", today.toString())
                        .param("toDate", today.minusDays(5).toString()), token))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("an org admin may override attendance; a manager may not")
    void manualOverrideIsAdminOnly() throws Exception {
        String adminToken = tokenFor(admin, ORG_ADMIN_PASSWORD);
        String managerToken = tokenFor(manager, MANAGER_PASSWORD);

        String payload = json(
                "userId", employee.getId(),
                "workDate", "2026-08-26",
                "checkInTime", "2026-08-26T05:30:00Z",
                "checkOutTime", "2026-08-26T13:00:00Z",
                "locationId", office.getId(),
                "reason", "Forgot to check in; confirmed with supervisor");
        String path = "/api/v1/organizations/" + org.getId() + "/attendance/override";

        mockMvc.perform(authed(put(path), managerToken).content(payload))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(
                        contains("organization admin")));

        mockMvc.perform(authed(put(path), adminToken).content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.manualOverride").value(true))
                .andExpect(jsonPath("$.overrideReason").value(
                        "Forgot to check in; confirmed with supervisor"))
                // 05:30 to 13:00 is 7.5 hours.
                .andExpect(jsonPath("$.workedHours").value(7.5));

        assertThat(notificationRepository.findAll())
                .filteredOn(n -> n.getType() == NotificationType.MANUAL_OVERRIDE)
                .extracting(n -> n.getUser().getId())
                .containsExactly(employee.getId());
    }

    @Test
    @DisplayName("an override amends an existing record rather than duplicating the day")
    void overrideAmendsExistingRecord() throws Exception {
        String employeeToken = tokenFor(employee, EMPLOYEE_PASSWORD);
        String adminToken = tokenFor(admin, ORG_ADMIN_PASSWORD);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        mockMvc.perform(authed(post("/api/v1/attendance/check-in"), employeeToken)
                        .content(checkInPayload(OFFICE_LAT, OFFICE_LON, null)))
                .andExpect(status().isOk());

        mockMvc.perform(authed(put("/api/v1/organizations/" + org.getId() + "/attendance/override"),
                        adminToken)
                        .content(json(
                                "userId", employee.getId(),
                                "workDate", today.toString(),
                                "checkInTime", today + "T08:00:00Z",
                                "checkOutTime", today + "T16:00:00Z",
                                "reason", "Corrected clock-in time")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workedHours").value(8.0));

        assertThat(attendanceRepository.findAll())
                .filteredOn(a -> a.getUser().getId().equals(employee.getId()))
                .hasSize(1);
    }

    @Test
    @DisplayName("an override with check-out before check-in is rejected")
    void overrideRejectsInvertedTimes() throws Exception {
        String adminToken = tokenFor(admin, ORG_ADMIN_PASSWORD);

        mockMvc.perform(authed(put("/api/v1/organizations/" + org.getId() + "/attendance/override"),
                        adminToken)
                        .content(json(
                                "userId", employee.getId(),
                                "workDate", "2026-08-26",
                                "checkInTime", "2026-08-26T13:00:00Z",
                                "checkOutTime", "2026-08-26T05:00:00Z",
                                "reason", "Bad data")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("check-in validates coordinates and requires a location")
    void checkInValidation() throws Exception {
        String token = tokenFor(employee, EMPLOYEE_PASSWORD);

        mockMvc.perform(authed(post("/api/v1/attendance/check-in"), token)
                        .content(json("latitude", 200.0, "longitude", 500.0)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.locationId").exists())
                .andExpect(jsonPath("$.fieldErrors.latitude").exists())
                .andExpect(jsonPath("$.fieldErrors.longitude").exists());
    }

    @Test
    @DisplayName("a platform super admin has no attendance to record")
    void superAdminCannotCheckIn() throws Exception {
        givenSuperAdmin("platform@test.local");
        String token = tokenFor("platform@test.local", SUPER_ADMIN_PASSWORD, null);

        mockMvc.perform(authed(post("/api/v1/attendance/check-in"), token)
                        .content(checkInPayload(OFFICE_LAT, OFFICE_LON, null)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("attendance is stamped with the record's own organization")
    void recordCarriesTenant() throws Exception {
        String token = tokenFor(employee, EMPLOYEE_PASSWORD);
        mockMvc.perform(authed(post("/api/v1/attendance/check-in"), token)
                        .content(checkInPayload(OFFICE_LAT, OFFICE_LON, null)))
                .andExpect(status().isOk());

        AttendanceRecord record = attendanceRepository.findAll().get(0);
        assertThat(record.getOrganization().getId()).isEqualTo(org.getId());
        assertThat(record.getCheckInTime()).isBefore(Instant.now().plusSeconds(1));
    }
}
