package com.attendance.api.service;

import com.attendance.api.domain.*;
import com.attendance.api.domain.enums.NotificationType;
import com.attendance.api.domain.enums.Role;
import com.attendance.api.dto.attendance.*;
import com.attendance.api.dto.common.PageResponse;
import com.attendance.api.dto.location.LocationResponse;
import com.attendance.api.exception.*;
import com.attendance.api.repository.*;
import com.attendance.api.security.SecurityUtils;
import com.attendance.api.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.*;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.lang.NonNull;

/**
 * Check-in / check-out with geofence enforcement, plus the org-admin manual override.
 *
 * <p>Calendar days are resolved in the <em>organization's</em> timezone, not the server's,
 * so a team in Asia/Karachi and one in America/New_York each get their own notion of "today".
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AttendanceService {

    private final AttendanceRecordRepository attendanceRepository;
    private final LeaveRequestRepository leaveRequestRepository;
    private final LocationRepository locationRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final AccessControlService accessControl;

    @Transactional
    public AttendanceResponse checkIn(CheckInRequest request) {
        UserPrincipal principal = SecurityUtils.requirePrincipal();
        User user = requireUser(principal.getId());
        Organization organization = requireOrganizationOf(user);
        LocalDate today = todayIn(organization);

        Location location = Require.found(
                locationRepository .findByIdAndOrganizationId(request.locationId(), organization.getId()),
                "Location", request.locationId());

        if (!location.isActive()) {
            throw new BusinessRuleException("That location is no longer active");
        }
        if (!isAssignedTo(user.getId(), location.getId())) {
            throw new AccessDeniedBusinessException(
                    "You are not assigned to " + location.getName());
        }
        if (attendanceRepository.findByUserIdAndWorkDate(user.getId(), today).isPresent()) {
            throw new ConflictException("You have already checked in today");
        }
        if (isOnApprovedLeave(organization.getId(), user.getId(), today)) {
            throw new BusinessRuleException(
                    "You are on approved leave today; no check-in is required");
        }

        boolean manual = evaluateGeofence(request, location, organization);
        Instant now = Instant.now();
        boolean late = isLate(now, organization);

        AttendanceRecord record = attendanceRepository.save(AttendanceRecord.builder()
                .organization(organization)
                .user(user)
                .location(location)
                .workDate(today)
                .checkInTime(now)
                .checkInLatitude(request.latitude())
                .checkInLongitude(request.longitude())
                .gpsAccuracyMeters(request.gpsAccuracyMeters())
                .late(late)
                .manualOverride(manual)
                .overrideReason(manual ? request.manualReason() : null)
                .build());

        log.info("Check-in: user={} location={} date={} late={} manual={}",
                user.getId(), location.getName(), today, late, manual);

        if (late) {
            notifyLateCheckIn(user, organization, record);
        }
        return AttendanceResponse.from(record);
    }

    /**
     * @return true when the check-in is being accepted as a manual (out-of-fence) entry
     * @throws GeofenceViolationException when outside the fence and no manual path applies
     */
    private boolean evaluateGeofence(CheckInRequest request, Location location,
                                     Organization organization) {
        double distance = GeoUtils.distanceMeters(
                request.latitude(), request.longitude(),
                location.getLatitude(), location.getLongitude());

        if (distance <= location.getGeofenceRadiusMeters()) {
            return false;
        }

        boolean manualPermitted = organization.isAllowManualCheckIn()
                && StringUtils.hasText(request.manualReason());
        if (!manualPermitted) {
            log.info("Rejected check-in outside geofence: {}m from {} (radius {}m)",
                    Math.round(distance), location.getName(), location.getGeofenceRadiusMeters());
            throw new GeofenceViolationException(distance, location.getGeofenceRadiusMeters());
        }

        log.info("Accepted manual check-in {}m outside {} with reason",
                Math.round(distance), location.getName());
        return true;
    }

    @Transactional
    public AttendanceResponse checkOut(CheckOutRequest request) {
        User user = requireUser(SecurityUtils.currentUserId());
        Organization organization = requireOrganizationOf(user);
        LocalDate today = todayIn(organization);

        AttendanceRecord record = Require.present(
                attendanceRepository.findByUserIdAndWorkDate(user.getId(), today),
                () -> new BusinessRuleException(
                        "You have not checked in today, so there is nothing to check out of"));

        if (record.getCheckOutTime() != null) {
            throw new ConflictException("You have already checked out today");
        }

        record.setCheckOutTime(Instant.now());
        if (request != null) {
            record.setCheckOutLatitude(request.latitude());
            record.setCheckOutLongitude(request.longitude());
        }

        AttendanceRecord saved = attendanceRepository.save(record);
        log.info("Check-out: user={} date={}", user.getId(), today);
        return AttendanceResponse.from(saved);
    }

    /** Single call powering the mobile home screen. */
    @Transactional(readOnly = true)
    public AttendanceStatusResponse currentStatus() {
        User user = requireUser(SecurityUtils.currentUserId());
        Organization organization = requireOrganizationOf(user);
        LocalDate today = todayIn(organization);

        Optional<AttendanceRecord> record =
                attendanceRepository.findByUserIdAndWorkDate(user.getId(), today);

        List<LocationResponse> locations = locationRepository.findAssignedToUser(user.getId())
                .stream().map(LocationResponse::from).toList();

        return new AttendanceStatusResponse(
                today,
                record.isPresent(),
                record.map(r -> r.getCheckOutTime() != null).orElse(false),
                record.map(AttendanceResponse::from).orElse(null),
                isOnApprovedLeave(organization.getId(), user.getId(), today),
                organization.isAllowManualCheckIn(),
                locations);
    }

    @Transactional(readOnly = true)
    public PageResponse<AttendanceResponse> history(UUID userId, UUID locationId,
                                                    LocalDate fromDate, LocalDate toDate,
                                                    Pageable pageable) {
        UUID orgId = accessControl.currentOrganization();
        List<UUID> visibleUserIds = accessControl.visibleUserIds(userId);

        if (fromDate != null && toDate != null && toDate.isBefore(fromDate)) {
            throw new BusinessRuleException("toDate must not be before fromDate");
        }

        return PageResponse.of(
                attendanceRepository.search(orgId,
                        QueryParams.isUnrestricted(visibleUserIds),
                        QueryParams.orPlaceholder(visibleUserIds),
                        locationId == null, QueryParams.orNil(locationId),
                        QueryParams.fromOrMin(fromDate), QueryParams.toOrMax(toDate),
                        pageable),
                AttendanceResponse::from);
    }

    /** The calling employee's own history. */
    @Transactional(readOnly = true)
    public PageResponse<AttendanceResponse> myHistory(LocalDate fromDate, LocalDate toDate,
                                                      Pageable pageable) {
        return history(SecurityUtils.currentUserId(), null, fromDate, toDate, pageable);
    }

    /**
     * Org-admin override. Creates the day's record when absent, otherwise amends it,
     * and always notifies the affected employee that their attendance was changed.
     */
    @Transactional
    public AttendanceResponse manualOverride(UUID organizationId, ManualAttendanceRequest request) {
        UUID orgId = accessControl.resolveOrganization(organizationId);
        accessControl.requireCanOverrideAttendance();

        User employee = accessControl.loadUserInOrganization(request.userId(), orgId);
        Organization organization = employee.getOrganization();
        User actor = requireUser(SecurityUtils.currentUserId());

        if (request.checkOutTime() != null && request.checkOutTime().isBefore(request.checkInTime())) {
            throw new BusinessRuleException("checkOutTime must not be before checkInTime");
        }

        Location location = null;
        if (request.locationId() != null) {
            location = Require.found(
                    locationRepository.findByIdAndOrganizationId(request.locationId(), orgId),
                    "Location", request.locationId());
        }

        AttendanceRecord record = attendanceRepository
                .findByUserIdAndWorkDate(employee.getId(), request.workDate())
                .orElseGet(() -> AttendanceRecord.builder()
                        .organization(organization)
                        .user(employee)
                        .workDate(request.workDate())
                        .build());

        record.setCheckInTime(request.checkInTime());
        record.setCheckOutTime(request.checkOutTime());
        if (location != null) {
            record.setLocation(location);
        }
        record.setLate(isLate(request.checkInTime(), organization));
        record.setManualOverride(true);
        record.setOverrideReason(request.reason());
        record.setOverriddenBy(actor);

        AttendanceRecord saved = attendanceRepository.save(record);

        log.info("Manual attendance override by {} for user {} on {}: {}",
                actor.getId(), employee.getId(), request.workDate(), request.reason());

        notificationService.notify(employee, organization, NotificationType.MANUAL_OVERRIDE,
                "Attendance updated by admin",
                String.format("Your attendance for %s was set by %s. Reason: %s",
                        request.workDate(), actor.getFullName(), request.reason()),
                saved.getId());

        return AttendanceResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public AttendanceResponse get(UUID attendanceId) {
        UUID orgId = accessControl.currentOrganization();
        AttendanceRecord record = Require.found(
                attendanceRepository.findByIdAndOrganizationId(attendanceId, orgId),
                "Attendance record", attendanceId);
        accessControl.requireCanViewUser(record.getUser().getId());
        return AttendanceResponse.from(record);
    }

    // ---------- helpers ----------

    /** Today's date in the organization's own timezone. */
    static LocalDate todayIn(Organization organization) {
        return LocalDate.now(zoneOf(organization));
    }

    static ZoneId zoneOf(Organization organization) {
        try {
            return ZoneId.of(organization.getTimezone());
        } catch (Exception e) {
            log.warn("Organization {} has invalid timezone '{}' ({}); falling back to UTC",
                    organization.getId(), organization.getTimezone(), e.getMessage());
            return ZoneOffset.UTC;
        }
    }

    /** Late when the local check-in time falls after the org's configured start hour. */
    static boolean isLate(Instant checkInTime, Organization organization) {
        LocalTime local = checkInTime.atZone(zoneOf(organization)).toLocalTime();
        return local.isAfter(LocalTime.of(organization.getWorkStartHour(), 0));
    }

    static long minutesLate(Instant checkInTime, Organization organization) {
        LocalTime local = checkInTime.atZone(zoneOf(organization)).toLocalTime();
        LocalTime start = LocalTime.of(organization.getWorkStartHour(), 0);
        return local.isAfter(start) ? Duration.between(start, local).toMinutes() : 0L;
    }

    private boolean isAssignedTo(UUID userId, UUID locationId) {
        return locationRepository.findAssignedToUser(userId).stream()
                .anyMatch(l -> l.getId().equals(locationId));
    }

    private boolean isOnApprovedLeave(UUID organizationId, UUID userId, LocalDate date) {
        return !leaveRequestRepository
                .findApprovedOverlapping(organizationId, date, date, false, List.of(userId))
                .isEmpty();
    }

    private void notifyLateCheckIn(User user, Organization organization, AttendanceRecord record) {
        long minutes = minutesLate(record.getCheckInTime(), organization);
        String message = String.format("Check-in recorded %d minute(s) after the %02d:00 start time.",
                minutes, organization.getWorkStartHour());

        notificationService.notify(user, organization, NotificationType.LATE_CHECK_IN,
                "Late check-in recorded", message, record.getId());

        // The spec routes late alerts to the employee's manager as well.
        User manager = user.getManager();
        if (manager != null && manager.isActive()) {
            notificationService.notify(manager, organization, NotificationType.LATE_CHECK_IN,
                    "Late check-in: " + user.getFullName(),
                    String.format("%s checked in %d minute(s) late.", user.getFullName(), minutes),
                    record.getId());
        }
    }

    @NonNull
    private User requireUser(@NonNull UUID userId) {
        return Require.found(userRepository.findById(userId), "User", userId);
    }

    private Organization requireOrganizationOf(User user) {
        if (user.getOrganization() == null) {
            throw new AccessDeniedBusinessException(
                    "Attendance is tenant-scoped; a platform super admin has no attendance to record");
        }
        if (user.getRole() == Role.SUPER_ADMIN) {
            throw new AccessDeniedBusinessException("A platform super admin does not record attendance");
        }
        return user.getOrganization();
    }
}
