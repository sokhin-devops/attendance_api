package com.attendance.api.integration;

import com.attendance.api.domain.*;
import com.attendance.api.domain.enums.LeaveStatus;
import com.attendance.api.domain.enums.LeaveType;
import com.attendance.api.domain.enums.Role;
import com.attendance.api.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * The core multi-tenant guarantee: knowing another organization's ids must not help you
 * read or write its data. Every check here uses a real, valid token for tenant A and a real
 * id belonging to tenant B.
 */
class TenantIsolationIntegrationTest extends IntegrationTestBase {

    private Organization tenantA;
    private Organization tenantB;
    private User adminA;
    private User adminB;
    private User employeeB;
    private Location locationB;
    private LeaveRequest leaveB;
    private AttendanceRecord attendanceB;

    @BeforeEach
    void setUpTwoTenants() {
        tenantA = givenOrganization("Acme", "acme");
        tenantB = givenOrganization("Globex", "globex");

        adminA = givenUser(tenantA, "admin@acme.test", ORG_ADMIN_PASSWORD, Role.ORG_ADMIN, null);
        adminB = givenUser(tenantB, "admin@globex.test", ORG_ADMIN_PASSWORD, Role.ORG_ADMIN, null);
        employeeB = givenUser(tenantB, "emp@globex.test", EMPLOYEE_PASSWORD, Role.EMPLOYEE, null);

        locationB = givenLocation(tenantB, "Globex HQ", 40.7128, -74.0060, 100);

        LocalDate day = LocalDate.of(2026, 8, 26);
        attendanceB = attendanceRepository.save(AttendanceRecord.builder()
                .organization(tenantB)
                .user(employeeB)
                .location(locationB)
                .workDate(day)
                .checkInTime(day.atTime(9, 0).toInstant(java.time.ZoneOffset.UTC))
                .build());

        leaveB = leaveRequestRepository.save(LeaveRequest.builder()
                .organization(tenantB)
                .employee(employeeB)
                .leaveType(LeaveType.ANNUAL)
                .fromDate(LocalDate.of(2026, 9, 1))
                .toDate(LocalDate.of(2026, 9, 2))
                .daysRequested(2)
                .status(LeaveStatus.PENDING)
                .requestedBy(employeeB)
                .build());
    }

    @Test
    @DisplayName("reading another tenant's organization is refused")
    void cannotReadForeignOrganization() throws Exception {
        String token = tokenFor(adminA, ORG_ADMIN_PASSWORD);

        mockMvc.perform(authed(get("/api/v1/organizations/" + tenantB.getId()), token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(contains("do not have access")));
    }

    @Test
    @DisplayName("updating another tenant's organization is refused")
    void cannotUpdateForeignOrganization() throws Exception {
        String token = tokenFor(adminA, ORG_ADMIN_PASSWORD);

        mockMvc.perform(authed(put("/api/v1/organizations/" + tenantB.getId()), token)
                        .content(json("name", "Hijacked")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("listing or creating users in another tenant is refused")
    void cannotTouchForeignUsers() throws Exception {
        String token = tokenFor(adminA, ORG_ADMIN_PASSWORD);

        mockMvc.perform(authed(get("/api/v1/organizations/" + tenantB.getId() + "/users"), token))
                .andExpect(status().isForbidden());

        mockMvc.perform(authed(post("/api/v1/organizations/" + tenantB.getId() + "/users"), token)
                        .content(json("email", "intruder@globex.test",
                                "password", EMPLOYEE_PASSWORD,
                                "firstName", "In", "lastName", "Truder", "role", "EMPLOYEE")))
                .andExpect(status().isForbidden());

        mockMvc.perform(authed(get("/api/v1/organizations/" + tenantB.getId()
                        + "/users/" + employeeB.getId()), token))
                .andExpect(status().isForbidden());

        mockMvc.perform(authed(delete("/api/v1/organizations/" + tenantB.getId()
                        + "/users/" + employeeB.getId()), token))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("reading or creating locations in another tenant is refused")
    void cannotTouchForeignLocations() throws Exception {
        String token = tokenFor(adminA, ORG_ADMIN_PASSWORD);

        mockMvc.perform(authed(get("/api/v1/organizations/" + tenantB.getId()
                        + "/locations"), token))
                .andExpect(status().isForbidden());

        mockMvc.perform(authed(get("/api/v1/organizations/" + tenantB.getId()
                        + "/locations/" + locationB.getId()), token))
                .andExpect(status().isForbidden());

        mockMvc.perform(authed(put("/api/v1/organizations/" + tenantB.getId()
                        + "/locations/" + locationB.getId()), token)
                        .content(json("geofenceRadiusMeters", 100000)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a foreign location cannot be assigned to a local user")
    void cannotAssignForeignLocation() throws Exception {
        User employeeA = givenUser(tenantA, "emp@acme.test", EMPLOYEE_PASSWORD,
                Role.EMPLOYEE, null);
        String token = tokenFor(adminA, ORG_ADMIN_PASSWORD);

        mockMvc.perform(authed(put("/api/v1/organizations/" + tenantA.getId()
                        + "/users/" + employeeA.getId() + "/locations"), token)
                        .content(json("locationIds", java.util.List.of(locationB.getId()))))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a foreign attendance record is invisible, not merely unauthorized")
    void cannotReadForeignAttendance() throws Exception {
        String token = tokenFor(adminA, ORG_ADMIN_PASSWORD);

        mockMvc.perform(authed(get("/api/v1/attendance/" + attendanceB.getId()), token))
                .andExpect(status().isNotFound());

        // The tenant-wide listing never leaks the other org's rows.
        mockMvc.perform(authed(get("/api/v1/attendance"), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    @DisplayName("overriding attendance in another tenant is refused")
    void cannotOverrideForeignAttendance() throws Exception {
        String token = tokenFor(adminA, ORG_ADMIN_PASSWORD);

        mockMvc.perform(authed(put("/api/v1/organizations/" + tenantB.getId()
                        + "/attendance/override"), token)
                        .content(json("userId", employeeB.getId(),
                                "workDate", "2026-08-26",
                                "checkInTime", "2026-08-26T09:00:00Z",
                                "reason", "Not my tenant")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a foreign leave request is invisible and cannot be decided")
    void cannotTouchForeignLeave() throws Exception {
        String token = tokenFor(adminA, ORG_ADMIN_PASSWORD);

        mockMvc.perform(authed(get("/api/v1/leave-requests/" + leaveB.getId()), token))
                .andExpect(status().isNotFound());

        mockMvc.perform(authed(put("/api/v1/leave-requests/" + leaveB.getId() + "/approve"), token)
                        .content("{}"))
                .andExpect(status().isNotFound());

        mockMvc.perform(authed(get("/api/v1/leave-requests"), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    @DisplayName("a foreign manager's team is refused, even with the caller's own org in the path")
    void cannotListForeignTeam() throws Exception {
        // A manager inside tenant B, with a direct report of their own.
        User managerB = givenUser(tenantB, "mgr@globex.test", MANAGER_PASSWORD, Role.MANAGER, null);
        givenUser(tenantB, "report@globex.test", EMPLOYEE_PASSWORD, Role.EMPLOYEE, managerB);

        String token = tokenFor(adminA, ORG_ADMIN_PASSWORD);

        // Naming tenant B in the path is refused outright.
        mockMvc.perform(authed(get("/api/v1/organizations/" + tenantB.getId()
                        + "/users/" + managerB.getId() + "/team"), token))
                .andExpect(status().isForbidden());

        // And substituting the caller's own org id must not smuggle the foreign manager
        // through: the manager has to belong to the tenant in the path.
        mockMvc.perform(authed(get("/api/v1/organizations/" + tenantA.getId()
                        + "/users/" + managerB.getId() + "/team"), token))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a foreign employee's leave balance is refused")
    void cannotReadForeignBalance() throws Exception {
        givenLeaveBalance(employeeB, LeaveType.ANNUAL, 2026, 20, 0);
        String token = tokenFor(adminA, ORG_ADMIN_PASSWORD);

        mockMvc.perform(authed(get("/api/v1/leave-balances/" + employeeB.getId()), token))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("reports and dashboards for another tenant are refused")
    void cannotReadForeignReports() throws Exception {
        String token = tokenFor(adminA, ORG_ADMIN_PASSWORD);
        String base = "/api/v1/organizations/" + tenantB.getId();

        mockMvc.perform(authed(get(base + "/dashboard/summary"), token))
                .andExpect(status().isForbidden());
        mockMvc.perform(authed(get(base + "/reports/attendance"), token))
                .andExpect(status().isForbidden());
        mockMvc.perform(authed(get(base + "/reports/lateness"), token))
                .andExpect(status().isForbidden());
        mockMvc.perform(authed(get(base + "/reports/leave"), token))
                .andExpect(status().isForbidden());
        mockMvc.perform(authed(get(base + "/reports/attendance.csv"), token))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("an org admin cannot list all organizations; a super admin can")
    void organizationListingIsSuperAdminOnly() throws Exception {
        mockMvc.perform(authed(get("/api/v1/organizations"), tokenFor(adminA, ORG_ADMIN_PASSWORD)))
                .andExpect(status().isForbidden());

        givenSuperAdmin("platform@test.local");
        mockMvc.perform(authed(get("/api/v1/organizations").param("size", "50"),
                        tokenFor("platform@test.local", SUPER_ADMIN_PASSWORD, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    @DisplayName("a super admin may target any organization by id")
    void superAdminCrossesTenants() throws Exception {
        givenSuperAdmin("platform@test.local");
        String token = tokenFor("platform@test.local", SUPER_ADMIN_PASSWORD, null);

        mockMvc.perform(authed(get("/api/v1/organizations/" + tenantA.getId()), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantKey").value("acme"));
        mockMvc.perform(authed(get("/api/v1/organizations/" + tenantB.getId()), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantKey").value("globex"));
    }

    @Test
    @DisplayName("the same email in two tenants stays two separate accounts")
    void sameEmailIsolatedPerTenant() throws Exception {
        givenUser(tenantA, "shared@example.test", EMPLOYEE_PASSWORD, Role.EMPLOYEE, null);
        givenUser(tenantB, "shared@example.test", EMPLOYEE_PASSWORD, Role.EMPLOYEE, null);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(JSON)
                        .content(json("email", "shared@example.test",
                                "password", EMPLOYEE_PASSWORD, "tenantKey", "acme")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.organizationId").value(tenantA.getId().toString()));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(JSON)
                        .content(json("email", "shared@example.test",
                                "password", EMPLOYEE_PASSWORD, "tenantKey", "globex")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.organizationId").value(tenantB.getId().toString()));
    }

    @Test
    @DisplayName("notifications are only ever visible to their own recipient")
    void notificationsArePrivate() throws Exception {
        Notification forB = notificationRepository.save(Notification.builder()
                .organization(tenantB)
                .user(employeeB)
                .type(com.attendance.api.domain.enums.NotificationType.GENERAL)
                .title("Globex only")
                .message("Internal notice")
                .read(false)
                .build());

        String token = tokenFor(adminA, ORG_ADMIN_PASSWORD);

        mockMvc.perform(authed(get("/api/v1/notifications"), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        mockMvc.perform(authed(put("/api/v1/notifications/" + forB.getId() + "/read"), token))
                .andExpect(status().isNotFound());

        mockMvc.perform(authed(delete("/api/v1/notifications/" + forB.getId()), token))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("an org admin cannot deactivate another tenant's organization")
    void cannotDeactivateForeignOrganization() throws Exception {
        mockMvc.perform(authed(delete("/api/v1/organizations/" + tenantB.getId()),
                        tokenFor(adminA, ORG_ADMIN_PASSWORD)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("an organization must retain at least one active admin")
    void lastAdminIsProtected() throws Exception {
        String token = tokenFor(adminB, ORG_ADMIN_PASSWORD);

        mockMvc.perform(authed(put("/api/v1/organizations/" + tenantB.getId()
                        + "/users/" + adminB.getId()), token)
                        .content(json("role", "EMPLOYEE")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(contains("only active admin")));
    }

    @Test
    @DisplayName("an admin cannot deactivate their own account")
    void cannotDeactivateSelf() throws Exception {
        String token = tokenFor(adminB, ORG_ADMIN_PASSWORD);

        mockMvc.perform(authed(delete("/api/v1/organizations/" + tenantB.getId()
                        + "/users/" + adminB.getId()), token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(contains("your own account")));
    }
}
