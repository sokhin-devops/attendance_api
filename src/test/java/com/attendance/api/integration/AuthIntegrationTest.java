package com.attendance.api.integration;

import com.attendance.api.domain.Organization;
import com.attendance.api.domain.User;
import com.attendance.api.domain.enums.Role;
import com.attendance.api.support.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AuthIntegrationTest extends IntegrationTestBase {

    @Test
    @DisplayName("login returns an access token, a refresh token and the user profile")
    void loginHappyPath() throws Exception {
        Organization org = givenOrganization("Acme", "acme");
        givenUser(org, "admin@acme.test", ORG_ADMIN_PASSWORD, Role.ORG_ADMIN, null);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(JSON)
                        .content(json("email", "admin@acme.test",
                                "password", ORG_ADMIN_PASSWORD,
                                "tenantKey", "acme")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresInSeconds").value(900))
                .andExpect(jsonPath("$.user.email").value("admin@acme.test"))
                .andExpect(jsonPath("$.user.role").value("ORG_ADMIN"))
                .andExpect(jsonPath("$.user.organizationId").value(org.getId().toString()))
                // The password hash must never appear in a response body.
                .andExpect(jsonPath("$.user.passwordHash").doesNotExist());
    }

    @Test
    @DisplayName("a wrong password is rejected with 401 and no hint about which half failed")
    void wrongPassword() throws Exception {
        Organization org = givenOrganization("Acme", "acme");
        givenUser(org, "admin@acme.test", ORG_ADMIN_PASSWORD, Role.ORG_ADMIN, null);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(JSON)
                        .content(json("email", "admin@acme.test",
                                "password", "WrongPass@1", "tenantKey", "acme")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid email or password"));
    }

    @Test
    @DisplayName("an unknown email is rejected with the same 401 message")
    void unknownEmail() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(JSON)
                        .content(json("email", "nobody@nowhere.test", "password", "Whatever@1")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid email or password"));
    }

    @Test
    @DisplayName("an email present in two tenants requires a tenantKey")
    void ambiguousEmailNeedsTenantKey() throws Exception {
        Organization acme = givenOrganization("Acme", "acme");
        Organization globex = givenOrganization("Globex", "globex");
        givenUser(acme, "shared@example.test", EMPLOYEE_PASSWORD, Role.EMPLOYEE, null);
        givenUser(globex, "shared@example.test", EMPLOYEE_PASSWORD, Role.EMPLOYEE, null);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(JSON)
                        .content(json("email", "shared@example.test",
                                "password", EMPLOYEE_PASSWORD)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        contains("more than one organization")));

        // With the tenant key the very same credentials succeed, and land in the right tenant.
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(JSON)
                        .content(json("email", "shared@example.test",
                                "password", EMPLOYEE_PASSWORD, "tenantKey", "globex")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.organizationId").value(globex.getId().toString()));
    }

    @Test
    @DisplayName("a deactivated account cannot log in")
    void deactivatedAccount() throws Exception {
        Organization org = givenOrganization("Acme", "acme");
        User employee = givenUser(org, "emp@acme.test", EMPLOYEE_PASSWORD, Role.EMPLOYEE, null);
        employee.setActive(false);
        userRepository.save(employee);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(JSON)
                        .content(json("email", "emp@acme.test",
                                "password", EMPLOYEE_PASSWORD, "tenantKey", "acme")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("This account has been deactivated"));
    }

    @Test
    @DisplayName("a user in a deactivated organization cannot log in")
    void deactivatedOrganization() throws Exception {
        Organization org = givenOrganization("Acme", "acme");
        givenUser(org, "emp@acme.test", EMPLOYEE_PASSWORD, Role.EMPLOYEE, null);
        org.setActive(false);
        organizationRepository.save(org);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(JSON)
                        .content(json("email", "emp@acme.test",
                                "password", EMPLOYEE_PASSWORD, "tenantKey", "acme")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("protected endpoints reject a missing, malformed or unsigned token")
    void tokenRequired() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("A valid access token is required"));

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer not.a.jwt"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Basic dXNlcjpwYXNz"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("refresh rotates the token: the old one stops working")
    void refreshRotatesToken() throws Exception {
        Organization org = givenOrganization("Acme", "acme");
        givenUser(org, "admin@acme.test", ORG_ADMIN_PASSWORD, Role.ORG_ADMIN, null);

        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(JSON)
                        .content(json("email", "admin@acme.test",
                                "password", ORG_ADMIN_PASSWORD, "tenantKey", "acme")))
                .andExpect(status().isOk())
                .andReturn();
        String firstRefresh = objectMapper
                .readTree(login.getResponse().getContentAsString())
                .get("refreshToken").asText();

        MvcResult refreshed = mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(JSON)
                        .content(json("refreshToken", firstRefresh)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andReturn();
        String secondRefresh = objectMapper
                .readTree(refreshed.getResponse().getContentAsString())
                .get("refreshToken").asText();

        assertThat(secondRefresh).isNotEqualTo(firstRefresh);

        // Replaying the rotated-out token must fail.
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(JSON)
                        .content(json("refreshToken", firstRefresh)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("an unrecognised refresh token is rejected")
    void bogusRefreshToken() throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(JSON)
                        .content(json("refreshToken", "definitely-not-issued-by-us")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("logout revokes every refresh token the user holds")
    void logoutRevokesAllSessions() throws Exception {
        Organization org = givenOrganization("Acme", "acme");
        User admin = givenUser(org, "admin@acme.test", ORG_ADMIN_PASSWORD, Role.ORG_ADMIN, null);

        // Two independent logins, i.e. two devices.
        String accessA = tokenFor(admin, ORG_ADMIN_PASSWORD);
        MvcResult loginB = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(JSON)
                        .content(json("email", "admin@acme.test",
                                "password", ORG_ADMIN_PASSWORD, "tenantKey", "acme")))
                .andReturn();
        String refreshB = objectMapper.readTree(loginB.getResponse().getContentAsString())
                .get("refreshToken").asText();

        mockMvc.perform(authed(post("/api/v1/auth/logout"), accessA)
                        .content(json("refreshToken", refreshB)))
                .andExpect(status().isOk());

        // The other device's refresh token is dead too.
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(JSON)
                        .content(json("refreshToken", refreshB)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("self-service signup creates the tenant, its admin and default leave balances")
    void registerOrganization() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register-organization")
                        .contentType(JSON)
                        .content(json(
                                "organizationName", "Initech",
                                "tenantKey", "initech",
                                "timezone", "Asia/Karachi",
                                "workStartHour", 9,
                                "workEndHour", 17,
                                "adminEmail", "admin@initech.test",
                                "adminPassword", ORG_ADMIN_PASSWORD,
                                "adminFirstName", "Bill",
                                "adminLastName", "Lumbergh")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.role").value("ORG_ADMIN"))
                .andExpect(jsonPath("$.accessToken").isNotEmpty());

        Organization created = organizationRepository.findByTenantKeyIgnoreCase("initech")
                .orElseThrow();
        assertThat(created.getTimezone()).isEqualTo("Asia/Karachi");
        assertThat(created.isAllowManualCheckIn()).isFalse();

        User admin = userRepository.findByEmailAndOrganizationId(
                "admin@initech.test", created.getId()).orElseThrow();
        assertThat(leaveBalanceRepository.findByUserIdAndYearOrderByLeaveType(
                admin.getId(), java.time.LocalDate.now().getYear())).isNotEmpty();
    }

    @Test
    @DisplayName("signup rejects a duplicate tenant key and a duplicate organization name")
    void duplicateSignupRejected() throws Exception {
        givenOrganization("Initech", "initech");

        mockMvc.perform(post("/api/v1/auth/register-organization")
                        .contentType(JSON)
                        .content(json("organizationName", "Initech Two", "tenantKey", "initech",
                                "adminEmail", "a@initech.test", "adminPassword", ORG_ADMIN_PASSWORD,
                                "adminFirstName", "A", "adminLastName", "B")))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/v1/auth/register-organization")
                        .contentType(JSON)
                        .content(json("organizationName", "Initech", "tenantKey", "initech-2",
                                "adminEmail", "a@initech.test", "adminPassword", ORG_ADMIN_PASSWORD,
                                "adminFirstName", "A", "adminLastName", "B")))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("signup enforces password complexity and tenant-key format")
    void signupValidation() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register-organization")
                        .contentType(JSON)
                        .content(json("organizationName", "Weak", "tenantKey", "Weak Key!",
                                "adminEmail", "not-an-email", "adminPassword", "short",
                                "adminFirstName", "", "adminLastName", "B")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.tenantKey").exists())
                .andExpect(jsonPath("$.fieldErrors.adminEmail").exists())
                .andExpect(jsonPath("$.fieldErrors.adminPassword").exists())
                .andExpect(jsonPath("$.fieldErrors.adminFirstName").exists());
    }

    @Test
    @DisplayName("changing a password invalidates the old one and revokes refresh tokens")
    void changePassword() throws Exception {
        Organization org = givenOrganization("Acme", "acme");
        User employee = givenUser(org, "emp@acme.test", EMPLOYEE_PASSWORD, Role.EMPLOYEE, null);
        String token = tokenFor(employee, EMPLOYEE_PASSWORD);

        mockMvc.perform(authed(post("/api/v1/auth/change-password"), token)
                        .content(json("currentPassword", EMPLOYEE_PASSWORD,
                                "newPassword", "Rotated@456")))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(JSON)
                        .content(json("email", "emp@acme.test",
                                "password", EMPLOYEE_PASSWORD, "tenantKey", "acme")))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(JSON)
                        .content(json("email", "emp@acme.test",
                                "password", "Rotated@456", "tenantKey", "acme")))
                .andExpect(status().isOk());

        assertThat(refreshTokenRepository.findAll())
                .filteredOn(rt -> rt.getUser().getId().equals(employee.getId()) && !rt.isRevoked())
                // Only the token minted by the successful post-change login remains live.
                .hasSize(1);
    }

    @Test
    @DisplayName("changing a password requires the correct current password")
    void changePasswordWrongCurrent() throws Exception {
        Organization org = givenOrganization("Acme", "acme");
        User employee = givenUser(org, "emp@acme.test", EMPLOYEE_PASSWORD, Role.EMPLOYEE, null);
        String token = tokenFor(employee, EMPLOYEE_PASSWORD);

        mockMvc.perform(authed(post("/api/v1/auth/change-password"), token)
                        .content(json("currentPassword", "NotIt@123",
                                "newPassword", "Rotated@456")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("/auth/me resolves the caller from the bearer token")
    void meEndpoint() throws Exception {
        Organization org = givenOrganization("Acme", "acme");
        User manager = givenUser(org, "mgr@acme.test", MANAGER_PASSWORD, Role.MANAGER, null);
        String token = tokenFor(manager, MANAGER_PASSWORD);

        mockMvc.perform(authed(get("/api/v1/auth/me"), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("mgr@acme.test"))
                .andExpect(jsonPath("$.role").value("MANAGER"))
                .andExpect(jsonPath("$.id").value(manager.getId().toString()));
    }

    @Test
    @DisplayName("the swagger docs endpoint is public and lists the API")
    void openApiIsPublic() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("Attendance API"))
                .andExpect(jsonPath("$.paths['/api/v1/auth/login']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/attendance/check-in']").exists());
    }
}
