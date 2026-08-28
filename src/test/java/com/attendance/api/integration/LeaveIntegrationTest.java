package com.attendance.api.integration;

import com.attendance.api.domain.LeaveBalance;
import com.attendance.api.domain.LeaveRequest;
import com.attendance.api.domain.Organization;
import com.attendance.api.domain.User;
import com.attendance.api.domain.enums.LeaveStatus;
import com.attendance.api.domain.enums.LeaveType;
import com.attendance.api.domain.enums.NotificationType;
import com.attendance.api.domain.enums.Role;
import com.attendance.api.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.lang.NonNull;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class LeaveIntegrationTest extends IntegrationTestBase {

    private static final int YEAR = 2026;
    private static final LocalDate FROM = LocalDate.of(YEAR, 9, 1);
    private static final LocalDate TO = LocalDate.of(YEAR, 9, 3);

    private Organization tenant;
    private User admin;
    private User manager;
    private User employee;
    private User unmanagedEmployee;

    @BeforeEach
    void setUpTenant() {
        tenant = givenOrganization("Acme", "acme");
        admin = givenUser(tenant, "admin@acme.test", ORG_ADMIN_PASSWORD, Role.ORG_ADMIN, null);
        manager = givenUser(tenant, "mgr@acme.test", MANAGER_PASSWORD, Role.MANAGER, null);
        employee = givenUser(tenant, "emp@acme.test", EMPLOYEE_PASSWORD, Role.EMPLOYEE, manager);
        unmanagedEmployee = givenUser(tenant, "emp2@acme.test", EMPLOYEE_PASSWORD,
                Role.EMPLOYEE, null);

        givenLeaveBalance(employee, LeaveType.ANNUAL, YEAR, 20, 0);
        givenLeaveBalance(employee, LeaveType.SICK, YEAR, 10, 0);
        givenLeaveBalance(unmanagedEmployee, LeaveType.ANNUAL, YEAR, 20, 0);
    }

    @NonNull
    private String submitPayload(LeaveType type, LocalDate from, LocalDate to) {
        return json("leaveType", type.name(), "fromDate", from.toString(),
                "toDate", to.toString(), "reason", "Personal");
    }

    private UUID submitAs(User user, String password, LeaveType type,
                          LocalDate from, LocalDate to) throws Exception {
        String token = tokenFor(user, password);
        MvcResult result = mockMvc.perform(authed(post("/api/v1/leave-requests"), token)
                        .content(submitPayload(type, from, to)))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper
                .readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    @Test
    @DisplayName("submitting a request computes inclusive days and notifies the manager")
    void submitRoutesToManager() throws Exception {
        String token = tokenFor(employee, EMPLOYEE_PASSWORD);

        mockMvc.perform(authed(post("/api/v1/leave-requests"), token)
                        .content(submitPayload(LeaveType.ANNUAL, FROM, TO)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.daysRequested").value(3))
                .andExpect(jsonPath("$.employeeId").value(employee.getId().toString()))
                .andExpect(jsonPath("$.requestedByName").exists());

        assertThat(notificationRepository.findAll())
                .filteredOn(n -> n.getType() == NotificationType.LEAVE_REQUEST_SUBMITTED)
                .extracting(n -> n.getUser().getId())
                .containsExactly(manager.getId());
    }

    @Test
    @DisplayName("a request from an employee with no manager goes to every org admin")
    void submitEscalatesToAdminsWhenNoManager() throws Exception {
        User secondAdmin = givenUser(tenant, "admin2@acme.test", ORG_ADMIN_PASSWORD,
                Role.ORG_ADMIN, null);

        submitAs(unmanagedEmployee, EMPLOYEE_PASSWORD, LeaveType.ANNUAL, FROM, TO);

        assertThat(notificationRepository.findAll())
                .filteredOn(n -> n.getType() == NotificationType.LEAVE_REQUEST_SUBMITTED)
                .extracting(n -> n.getUser().getId())
                .containsExactlyInAnyOrder(admin.getId(), secondAdmin.getId());
    }

    @Test
    @DisplayName("an overlapping pending or approved request is rejected")
    void overlappingRequestRejected() throws Exception {
        submitAs(employee, EMPLOYEE_PASSWORD, LeaveType.ANNUAL, FROM, TO);
        String token = tokenFor(employee, EMPLOYEE_PASSWORD);

        // Overlaps the middle of the existing range.
        mockMvc.perform(authed(post("/api/v1/leave-requests"), token)
                        .content(submitPayload(LeaveType.SICK, TO, TO.plusDays(2))))
                .andExpect(status().isConflict());

        // A range that starts after the existing one is fine.
        mockMvc.perform(authed(post("/api/v1/leave-requests"), token)
                        .content(submitPayload(LeaveType.SICK, TO.plusDays(1), TO.plusDays(2))))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("a request beyond the remaining balance is refused at submission")
    void insufficientBalanceRefusedEarly() throws Exception {
        String token = tokenFor(employee, EMPLOYEE_PASSWORD);

        mockMvc.perform(authed(post("/api/v1/leave-requests"), token)
                        .content(submitPayload(LeaveType.ANNUAL,
                                LocalDate.of(YEAR, 11, 1), LocalDate.of(YEAR, 12, 31))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(contains("Insufficient ANNUAL balance")));

        assertThat(leaveRequestRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("UNPAID leave is not capped by an entitlement")
    void unpaidLeaveIsNotCapped() throws Exception {
        String token = tokenFor(employee, EMPLOYEE_PASSWORD);

        mockMvc.perform(authed(post("/api/v1/leave-requests"), token)
                        .content(submitPayload(LeaveType.UNPAID,
                                LocalDate.of(YEAR, 11, 1), LocalDate.of(YEAR, 12, 31))))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("an inverted date range is rejected")
    void invertedRangeRejected() throws Exception {
        String token = tokenFor(employee, EMPLOYEE_PASSWORD);

        mockMvc.perform(authed(post("/api/v1/leave-requests"), token)
                        .content(submitPayload(LeaveType.ANNUAL, TO, FROM)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("an employee cannot file on someone else's behalf")
    void employeeCannotFileForOthers() throws Exception {
        String token = tokenFor(employee, EMPLOYEE_PASSWORD);

        mockMvc.perform(authed(post("/api/v1/leave-requests"), token)
                        .content(json("leaveType", "ANNUAL",
                                "fromDate", FROM.toString(), "toDate", TO.toString(),
                                "employeeId", unmanagedEmployee.getId())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("an org admin may file on an employee's behalf")
    void adminCanFileForEmployee() throws Exception {
        String token = tokenFor(admin, ORG_ADMIN_PASSWORD);

        mockMvc.perform(authed(post("/api/v1/leave-requests"), token)
                        .content(json("leaveType", "ANNUAL",
                                "fromDate", FROM.toString(), "toDate", TO.toString(),
                                "employeeId", employee.getId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.employeeId").value(employee.getId().toString()))
                .andExpect(jsonPath("$.requestedById").value(admin.getId().toString()));
    }

    @Test
    @DisplayName("approval debits the balance and notifies the employee")
    void approvalDebitsBalance() throws Exception {
        UUID requestId = submitAs(employee, EMPLOYEE_PASSWORD, LeaveType.ANNUAL, FROM, TO);
        String managerToken = tokenFor(manager, MANAGER_PASSWORD);

        mockMvc.perform(authed(put("/api/v1/leave-requests/" + requestId + "/approve"), managerToken)
                        .content(json("note", "Cover arranged")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.approvedById").value(manager.getId().toString()))
                .andExpect(jsonPath("$.decisionNote").value("Cover arranged"))
                .andExpect(jsonPath("$.decidedAt").exists());

        LeaveBalance balance = leaveBalanceRepository
                .findByUserIdAndLeaveTypeAndYear(employee.getId(), LeaveType.ANNUAL, YEAR)
                .orElseThrow();
        assertThat(balance.getUsedDays()).isEqualTo(3);
        assertThat(balance.getRemainingDays()).isEqualTo(17);

        assertThat(notificationRepository.findAll())
                .filteredOn(n -> n.getType() == NotificationType.LEAVE_APPROVED)
                .extracting(n -> n.getUser().getId())
                .containsExactly(employee.getId());
    }

    @Test
    @DisplayName("rejection leaves the balance untouched and notifies the employee")
    void rejectionDoesNotDebit() throws Exception {
        UUID requestId = submitAs(employee, EMPLOYEE_PASSWORD, LeaveType.ANNUAL, FROM, TO);
        String managerToken = tokenFor(manager, MANAGER_PASSWORD);

        mockMvc.perform(authed(put("/api/v1/leave-requests/" + requestId + "/reject"), managerToken)
                        .content(json("note", "Peak season")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));

        assertThat(leaveBalanceRepository
                .findByUserIdAndLeaveTypeAndYear(employee.getId(), LeaveType.ANNUAL, YEAR)
                .orElseThrow().getUsedDays()).isZero();

        assertThat(notificationRepository.findAll())
                .filteredOn(n -> n.getType() == NotificationType.LEAVE_REJECTED)
                .hasSize(1);
    }

    @Test
    @DisplayName("a manager may not approve their own request")
    void selfApprovalRefused() throws Exception {
        givenLeaveBalance(manager, LeaveType.ANNUAL, YEAR, 20, 0);
        UUID requestId = submitAs(manager, MANAGER_PASSWORD, LeaveType.ANNUAL, FROM, TO);
        String token = tokenFor(manager, MANAGER_PASSWORD);

        mockMvc.perform(authed(put("/api/v1/leave-requests/" + requestId + "/approve"), token)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(contains("your own leave request")));
    }

    @Test
    @DisplayName("an employee has no approval capability at all")
    void employeeCannotApprove() throws Exception {
        UUID requestId = submitAs(employee, EMPLOYEE_PASSWORD, LeaveType.ANNUAL, FROM, TO);

        mockMvc.perform(authed(put("/api/v1/leave-requests/" + requestId + "/approve"),
                        tokenFor(employee, EMPLOYEE_PASSWORD)).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a manager may decide only for direct reports; an org admin for anyone")
    void decisionAuthority() throws Exception {
        UUID outsideTeam = submitAs(unmanagedEmployee, EMPLOYEE_PASSWORD,
                LeaveType.ANNUAL, FROM, TO);
        String managerToken = tokenFor(manager, MANAGER_PASSWORD);
        String adminToken = tokenFor(admin, ORG_ADMIN_PASSWORD);

        mockMvc.perform(authed(put("/api/v1/leave-requests/" + outsideTeam + "/approve"),
                        managerToken).content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(contains("direct reports")));

        mockMvc.perform(authed(put("/api/v1/leave-requests/" + outsideTeam + "/approve"),
                        adminToken).content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a decided request cannot be decided again")
    void doubleDecisionRefused() throws Exception {
        UUID requestId = submitAs(employee, EMPLOYEE_PASSWORD, LeaveType.ANNUAL, FROM, TO);
        String managerToken = tokenFor(manager, MANAGER_PASSWORD);

        mockMvc.perform(authed(put("/api/v1/leave-requests/" + requestId + "/approve"),
                        managerToken).content("{}"))
                .andExpect(status().isOk());
        mockMvc.perform(authed(put("/api/v1/leave-requests/" + requestId + "/reject"),
                        managerToken).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("an employee may withdraw a pending request but not an approved one")
    void employeeCancellation() throws Exception {
        UUID requestId = submitAs(employee, EMPLOYEE_PASSWORD, LeaveType.ANNUAL, FROM, TO);
        String employeeToken = tokenFor(employee, EMPLOYEE_PASSWORD);

        mockMvc.perform(authed(put("/api/v1/leave-requests/" + requestId + "/cancel"),
                        employeeToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        UUID second = submitAs(employee, EMPLOYEE_PASSWORD, LeaveType.SICK,
                TO.plusDays(5), TO.plusDays(6));
        mockMvc.perform(authed(put("/api/v1/leave-requests/" + second + "/approve"),
                        tokenFor(manager, MANAGER_PASSWORD)).content("{}"))
                .andExpect(status().isOk());

        mockMvc.perform(authed(put("/api/v1/leave-requests/" + second + "/cancel"), employeeToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("an admin cancelling an approved request credits the days back")
    void adminCancellationCreditsBalance() throws Exception {
        UUID requestId = submitAs(employee, EMPLOYEE_PASSWORD, LeaveType.ANNUAL, FROM, TO);
        mockMvc.perform(authed(put("/api/v1/leave-requests/" + requestId + "/approve"),
                        tokenFor(manager, MANAGER_PASSWORD)).content("{}"))
                .andExpect(status().isOk());

        assertThat(leaveBalanceRepository
                .findByUserIdAndLeaveTypeAndYear(employee.getId(), LeaveType.ANNUAL, YEAR)
                .orElseThrow().getUsedDays()).isEqualTo(3);

        mockMvc.perform(authed(put("/api/v1/leave-requests/" + requestId + "/cancel"),
                        tokenFor(admin, ORG_ADMIN_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(leaveBalanceRepository
                .findByUserIdAndLeaveTypeAndYear(employee.getId(), LeaveType.ANNUAL, YEAR)
                .orElseThrow().getUsedDays()).isZero();
    }

    @Test
    @DisplayName("listing scopes to the caller: employee self, manager team, admin org")
    void listingVisibility() throws Exception {
        submitAs(employee, EMPLOYEE_PASSWORD, LeaveType.ANNUAL, FROM, TO);
        submitAs(unmanagedEmployee, EMPLOYEE_PASSWORD, LeaveType.ANNUAL, FROM, TO);

        mockMvc.perform(authed(get("/api/v1/leave-requests/me"), tokenFor(employee, EMPLOYEE_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));

        // The manager sees their report's request but not the unmanaged employee's.
        mockMvc.perform(authed(get("/api/v1/leave-requests"), tokenFor(manager, MANAGER_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));

        mockMvc.perform(authed(get("/api/v1/leave-requests"), tokenFor(admin, ORG_ADMIN_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    @DisplayName("the pending filter builds an approval queue")
    void pendingFilter() throws Exception {
        UUID first = submitAs(employee, EMPLOYEE_PASSWORD, LeaveType.ANNUAL, FROM, TO);
        submitAs(employee, EMPLOYEE_PASSWORD, LeaveType.SICK, TO.plusDays(5), TO.plusDays(6));

        mockMvc.perform(authed(put("/api/v1/leave-requests/" + first + "/approve"),
                        tokenFor(manager, MANAGER_PASSWORD)).content("{}"))
                .andExpect(status().isOk());

        mockMvc.perform(authed(get("/api/v1/leave-requests").param("status", "PENDING"),
                        tokenFor(admin, ORG_ADMIN_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));

        mockMvc.perform(authed(get("/api/v1/leave-requests").param("status", "APPROVED"),
                        tokenFor(admin, ORG_ADMIN_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @DisplayName("balances are readable by self, manager and admin but not by a stranger")
    void balanceVisibility() throws Exception {
        mockMvc.perform(authed(get("/api/v1/leave-balances/me"),
                        tokenFor(employee, EMPLOYEE_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        mockMvc.perform(authed(get("/api/v1/leave-balances/" + employee.getId()),
                        tokenFor(manager, MANAGER_PASSWORD)))
                .andExpect(status().isOk());

        // An employee reaching for a colleague's balance is refused.
        mockMvc.perform(authed(get("/api/v1/leave-balances/" + unmanagedEmployee.getId()),
                        tokenFor(employee, EMPLOYEE_PASSWORD)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("an admin can adjust an entitlement, but not below days already used")
    void adjustBalance() throws Exception {
        String adminToken = tokenFor(admin, ORG_ADMIN_PASSWORD);
        String path = "/api/v1/organizations/" + tenant.getId()
                + "/leave-balances/" + employee.getId() + "/ANNUAL";

        mockMvc.perform(authed(put(path).param("year", String.valueOf(YEAR)), adminToken)
                        .content(json("totalDays", 25)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalDays").value(25))
                .andExpect(jsonPath("$.remainingDays").value(25));

        mockMvc.perform(authed(put(path).param("year", String.valueOf(YEAR)), adminToken)
                        .content(json("totalDays", 5, "usedDays", 10)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(contains("cannot exceed total days")));
    }

    @Test
    @DisplayName("an employee cannot adjust their own entitlement")
    void employeeCannotAdjustBalance() throws Exception {
        mockMvc.perform(authed(put("/api/v1/organizations/" + tenant.getId()
                                + "/leave-balances/" + employee.getId() + "/ANNUAL"),
                        tokenFor(employee, EMPLOYEE_PASSWORD))
                        .content(json("totalDays", 999)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("rollover seeds missing entitlement slots and is idempotent")
    void rollover() throws Exception {
        String adminToken = tokenFor(admin, ORG_ADMIN_PASSWORD);
        String path = "/api/v1/organizations/" + tenant.getId() + "/leave-balances/rollover";

        MvcResult first = mockMvc.perform(authed(post(path).param("year", "2027"), adminToken))
                .andExpect(status().isOk())
                .andReturn();
        int created = objectMapper.readTree(first.getResponse().getContentAsString())
                .get("slotsCreated").asInt();
        assertThat(created).isPositive();

        mockMvc.perform(authed(post(path).param("year", "2027"), adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slotsCreated").value(0));
    }

    @Test
    @DisplayName("approved leave shows as leave, not absence, in the attendance report")
    void approvedLeaveShowsInReport() throws Exception {
        // A Tuesday, so the day counts as a working day in the report.
        LocalDate leaveDay = LocalDate.of(2026, 9, 1);
        leaveRequestRepository.save(LeaveRequest.builder()
                .organization(tenant)
                .employee(employee)
                .leaveType(LeaveType.ANNUAL)
                .fromDate(leaveDay)
                .toDate(leaveDay)
                .daysRequested(1)
                .status(LeaveStatus.APPROVED)
                .requestedBy(employee)
                .approvedBy(manager)
                .build());

        mockMvc.perform(authed(get("/api/v1/organizations/" + tenant.getId()
                                + "/reports/attendance")
                                .param("fromDate", leaveDay.toString())
                                .param("toDate", leaveDay.toString()),
                        tokenFor(employee, EMPLOYEE_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].workingDays").value(1))
                .andExpect(jsonPath("$[0].leaveDays").value(1))
                .andExpect(jsonPath("$[0].absentDays").value(0));
    }
}
