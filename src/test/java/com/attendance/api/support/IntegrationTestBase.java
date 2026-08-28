package com.attendance.api.support;

import com.attendance.api.domain.*;
import com.attendance.api.domain.enums.Role;
import com.attendance.api.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hamcrest.Matcher;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * Shared harness for the HTTP-level integration tests.
 *
 * <p>Requests go through the real filter chain with real bearer tokens, so tenant isolation
 * and role checks are exercised exactly as a client would hit them. Each test starts from a
 * clean tenant set; the platform super admin is created explicitly where a test needs one.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class IntegrationTestBase {

    /** Non-null handle on the JSON media type for use in request builders. */
    @NonNull
    protected static final MediaType JSON;

    static {
        MediaType applicationJson = MediaType.APPLICATION_JSON;
        if (applicationJson == null) {
            throw new IllegalStateException("MediaType.APPLICATION_JSON is unavailable");
        }
        JSON = applicationJson;
    }

    protected static final String SUPER_ADMIN_PASSWORD = "SuperAdmin@123";
    protected static final String ORG_ADMIN_PASSWORD = "OrgAdmin@123";
    protected static final String MANAGER_PASSWORD = "Manager@123";
    protected static final String EMPLOYEE_PASSWORD = "Employee@123";

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected OrganizationRepository organizationRepository;

    @Autowired
    protected UserRepository userRepository;

    @Autowired
    protected LocationRepository locationRepository;

    @Autowired
    protected UserLocationRepository userLocationRepository;

    @Autowired
    protected AttendanceRecordRepository attendanceRepository;

    @Autowired
    protected LeaveRequestRepository leaveRequestRepository;

    @Autowired
    protected LeaveBalanceRepository leaveBalanceRepository;

    @Autowired
    protected NotificationRepository notificationRepository;

    @Autowired
    protected RefreshTokenRepository refreshTokenRepository;

    @Autowired
    protected PasswordEncoder passwordEncoder;

    /**
     * Deleting organizations cascades to their users, locations, attendance and leave rows.
     * Platform users carry a null organization, so they are removed separately.
     */
    @BeforeEach
    @Transactional
    void resetDatabase() {
        organizationRepository.deleteAll();
        userRepository.findAllByEmail("platform@test.local")
                .forEach(userRepository::delete);
        userRepository.findAll().stream()
                .filter(u -> u.getOrganization() == null)
                .forEach(userRepository::delete);
    }

    // ---------- fixture builders ----------

    @NonNull
    protected Organization givenOrganization(String name, String tenantKey, String timezone,
                                             int startHour, boolean allowManualCheckIn) {
        return organizationRepository.save(Organization.builder()
                .name(name)
                .tenantKey(tenantKey)
                .timezone(timezone)
                .workStartHour(startHour)
                .workEndHour(17)
                .allowManualCheckIn(allowManualCheckIn)
                .active(true)
                .build());
    }

    @NonNull
    protected Organization givenOrganization(String name, String tenantKey) {
        return givenOrganization(name, tenantKey, "UTC", 9, false);
    }

    @NonNull
    protected User givenUser(Organization organization, String email, String password,
                             Role role, User manager) {
        return userRepository.save(User.builder()
                .organization(organization)
                .email(email)
                .passwordHash(passwordEncoder.encode(password))
                .firstName(role.name().charAt(0) + role.name().substring(1).toLowerCase())
                .lastName("Tester")
                .role(role)
                .manager(manager)
                .active(true)
                .build());
    }

    @NonNull
    protected User givenSuperAdmin(String email) {
        return userRepository.save(User.builder()
                .organization(null)
                .email(email)
                .passwordHash(passwordEncoder.encode(SUPER_ADMIN_PASSWORD))
                .firstName("Platform")
                .lastName("Admin")
                .role(Role.SUPER_ADMIN)
                .active(true)
                .build());
    }

    @NonNull
    protected Location givenLocation(Organization organization, String name,
                                     double latitude, double longitude, int radiusMeters) {
        return locationRepository.save(Location.builder()
                .organization(organization)
                .name(name)
                .address("Test address")
                .latitude(latitude)
                .longitude(longitude)
                .geofenceRadiusMeters(radiusMeters)
                .active(true)
                .build());
    }

    protected void assignLocation(User user, Location location) {
        userLocationRepository.save(UserLocation.builder()
                .userId(user.getId())
                .locationId(location.getId())
                .build());
    }

    @NonNull
    protected LeaveBalance givenLeaveBalance(User user, com.attendance.api.domain.enums.LeaveType type,
                                             int year, int totalDays, int usedDays) {
        return leaveBalanceRepository.save(LeaveBalance.builder()
                .organization(user.getOrganization())
                .user(user)
                .leaveType(type)
                .year(year)
                .totalDays(totalDays)
                .usedDays(usedDays)
                .build());
    }

    // ---------- HTTP helpers ----------

    /** Logs in and returns the access token, failing the test if login does not succeed. */
    @NonNull
    protected String tokenFor(String email, String password, String tenantKey) throws Exception {
        String payload = tenantKey == null
                ? json("email", email, "password", password)
                : json("email", email, "password", password, "tenantKey", tenantKey);

        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(JSON)
                        .content(payload))
                .andReturn();

        if (result.getResponse().getStatus() != 200) {
            throw new AssertionError("Login failed for " + email + ": "
                    + result.getResponse().getStatus() + " "
                    + result.getResponse().getContentAsString());
        }
        String accessToken = objectMapper.readTree(result.getResponse().getContentAsString())
                .path("accessToken").asText(null);
        if (accessToken == null || accessToken.isBlank()) {
            throw new AssertionError("Login response carried no accessToken for " + email);
        }
        return accessToken;
    }

    @NonNull
    protected String tokenFor(User user, String password) throws Exception {
        Organization organization = user.getOrganization();
        String tenantKey = organization == null ? null : organization.getTenantKey();
        return tokenFor(user.getEmail(), password, tenantKey);
    }

    @NonNull
    protected MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder,
                                                   String token) {
        return builder.header("Authorization", "Bearer " + token)
                .contentType(JSON);
    }

    /**
     * Non-null wrapper around Hamcrest's {@code containsString}, which is unannotated and
     * would otherwise be rejected by every {@code jsonPath(...).value(matcher)} assertion.
     */
    @NonNull
    protected static Matcher<? super String> contains(String substring) {
        Matcher<String> matcher = org.hamcrest.Matchers.containsString(substring);
        if (matcher == null) {
            throw new IllegalStateException("Hamcrest returned no matcher");
        }
        return matcher;
    }

    /** Builds a flat JSON object from alternating key/value arguments. */
    @NonNull
    protected String json(Object... keyValuePairs) {
        if (keyValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException("json() needs alternating keys and values");
        }
        var node = objectMapper.createObjectNode();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            String key = String.valueOf(keyValuePairs[i]);
            Object value = keyValuePairs[i + 1];
            if (value == null) {
                node.putNull(key);
            } else if (value instanceof Integer v) {
                node.put(key, v);
            } else if (value instanceof Double v) {
                node.put(key, v);
            } else if (value instanceof Boolean v) {
                node.put(key, v);
            } else if (value instanceof List<?> v) {
                var array = node.putArray(key);
                v.forEach(item -> array.add(String.valueOf(item)));
            } else {
                node.put(key, String.valueOf(value));
            }
        }
        String payload = node.toString();
        if (payload == null) {
            throw new IllegalStateException("Failed to render the JSON payload");
        }
        return payload;
    }
}
