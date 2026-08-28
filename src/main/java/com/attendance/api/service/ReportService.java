package com.attendance.api.service;

import com.attendance.api.domain.AttendanceRecord;
import com.attendance.api.domain.LeaveBalance;
import com.attendance.api.domain.LeaveRequest;
import com.attendance.api.domain.Organization;
import com.attendance.api.domain.User;
import com.attendance.api.domain.enums.LeaveStatus;
import com.attendance.api.dto.report.*;
import com.attendance.api.exception.BusinessRuleException;
import com.attendance.api.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Aggregations behind the dashboards and CSV exports.
 *
 * <p>Working days exclude weekends. Each calendar day for each employee resolves to exactly
 * one of PRESENT / LATE / ON_LEAVE / ABSENT, so the rates on a row always sum sensibly.
 * Rows are restricted by {@link AccessControlService#visibleUserIds} so a manager's report
 * covers their team and an employee's covers only themselves.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReportService {

    /** Guardrail: a single report request may not span more than roughly two years. */
    private static final long MAX_REPORT_DAYS = 800;

    private final AttendanceRecordRepository attendanceRepository;
    private final LeaveRequestRepository leaveRequestRepository;
    private final LeaveBalanceRepository leaveBalanceRepository;
    private final UserRepository userRepository;
    private final OrganizationService organizationService;
    private final AccessControlService accessControl;

    @Transactional(readOnly = true)
    public List<AttendanceReportRow> attendanceReport(UUID organizationId, LocalDate fromDate,
                                                      LocalDate toDate, UUID userId) {
        UUID orgId = accessControl.resolveOrganization(organizationId);
        DateWindow window = validateWindow(fromDate, toDate);
        List<UUID> visibleIds = accessControl.visibleUserIds(userId);

        List<User> employees = employeesInScope(orgId, visibleIds);
        if (employees.isEmpty()) {
            return List.of();
        }

        List<UUID> employeeIds = employees.stream().map(User::getId).toList();
        Map<UUID, List<AttendanceRecord>> attendanceByUser = attendanceRepository
                .findForReport(orgId, window.from(), window.to(), false, employeeIds)
                .stream()
                .collect(Collectors.groupingBy(r -> r.getUser().getId()));

        Map<UUID, Set<LocalDate>> leaveDaysByUser = approvedLeaveDays(
                orgId, window.from(), window.to(), employeeIds);

        List<LocalDate> workingDays = workingDaysBetween(window.from(), window.to());

        return employees.stream().map(employee -> {
            List<AttendanceRecord> records =
                    attendanceByUser.getOrDefault(employee.getId(), List.of());
            Set<LocalDate> attendedDays = records.stream()
                    .map(AttendanceRecord::getWorkDate)
                    .collect(Collectors.toSet());
            Set<LocalDate> lateDays = records.stream()
                    .filter(AttendanceRecord::isLate)
                    .map(AttendanceRecord::getWorkDate)
                    .collect(Collectors.toSet());
            Set<LocalDate> leaveDays = leaveDaysByUser.getOrDefault(employee.getId(), Set.of());

            int present = 0;
            int late = 0;
            int onLeave = 0;
            int absent = 0;

            for (LocalDate day : workingDays) {
                if (attendedDays.contains(day)) {
                    // A late day is still a present day; it is counted in both buckets.
                    present++;
                    if (lateDays.contains(day)) {
                        late++;
                    }
                } else if (leaveDays.contains(day)) {
                    onLeave++;
                } else {
                    absent++;
                }
            }

            double totalHours = records.stream()
                    .filter(r -> r.getCheckOutTime() != null)
                    .mapToDouble(r -> Duration.between(
                            r.getCheckInTime(), r.getCheckOutTime()).toMinutes() / 60.0)
                    .sum();

            int totalWorkingDays = workingDays.size();
            return new AttendanceReportRow(
                    employee.getId(),
                    employee.getFullName(),
                    employee.getEmail(),
                    totalWorkingDays,
                    present,
                    late,
                    onLeave,
                    absent,
                    rate(present, totalWorkingDays),
                    rate(late, present),
                    rate(absent, totalWorkingDays),
                    round2(totalHours));
        }).toList();
    }

    @Transactional(readOnly = true)
    public List<LatenessReportRow> latenessReport(UUID organizationId, LocalDate fromDate,
                                                  LocalDate toDate, UUID userId) {
        UUID orgId = accessControl.resolveOrganization(organizationId);
        DateWindow window = validateWindow(fromDate, toDate);
        List<UUID> visibleIds = accessControl.visibleUserIds(userId);

        Organization organization = organizationService.requireOrganization(orgId);
        List<User> employees = employeesInScope(orgId, visibleIds);
        if (employees.isEmpty()) {
            return List.of();
        }

        Map<UUID, List<AttendanceRecord>> byUser = attendanceRepository
                .findForReport(orgId, window.from(), window.to(), false,
                        employees.stream().map(User::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(r -> r.getUser().getId()));

        return employees.stream().map(employee -> {
            List<AttendanceRecord> records = byUser.getOrDefault(employee.getId(), List.of());
            List<AttendanceRecord> lateRecords = records.stream()
                    .filter(AttendanceRecord::isLate)
                    .toList();

            Double averageMinutes = lateRecords.isEmpty() ? null : round2(lateRecords.stream()
                    .mapToLong(r -> AttendanceService.minutesLate(r.getCheckInTime(), organization))
                    .average()
                    .orElse(0));

            return new LatenessReportRow(
                    employee.getId(),
                    employee.getFullName(),
                    employee.getEmail(),
                    records.size(),
                    lateRecords.size(),
                    rate(lateRecords.size(), records.size()),
                    averageMinutes);
        }).toList();
    }

    @Transactional(readOnly = true)
    public List<LeaveReportRow> leaveReport(UUID organizationId, Integer year, UUID userId) {
        UUID orgId = accessControl.resolveOrganization(organizationId);
        int targetYear = year == null ? LocalDate.now().getYear() : year;
        List<UUID> visibleIds = accessControl.visibleUserIds(userId);

        List<User> employees = employeesInScope(orgId, visibleIds);
        if (employees.isEmpty()) {
            return List.of();
        }

        List<UUID> employeeIds = employees.stream().map(User::getId).toList();
        Map<UUID, User> employeeById = employees.stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));

        List<LeaveBalance> balances =
                leaveBalanceRepository.findForReport(orgId, targetYear, false, employeeIds);

        // Pending days per (user, type) so a row shows what is still in flight.
        Map<String, Integer> pendingDays = leaveRequestRepository
                .search(orgId, false, employeeIds, false, LeaveStatus.PENDING,
                        LocalDate.of(targetYear, 1, 1), LocalDate.of(targetYear, 12, 31),
                        org.springframework.data.domain.Pageable.unpaged())
                .getContent()
                .stream()
                .collect(Collectors.groupingBy(
                        lr -> lr.getEmployee().getId() + "|" + lr.getLeaveType(),
                        Collectors.summingInt(LeaveRequest::getDaysRequested)));

        return balances.stream()
                .sorted(Comparator
                        .comparing((LeaveBalance b) -> b.getUser().getFullName())
                        .thenComparing(b -> b.getLeaveType().name()))
                .map(balance -> {
                    User employee = employeeById.getOrDefault(
                            balance.getUser().getId(), balance.getUser());
                    String key = employee.getId() + "|" + balance.getLeaveType();
                    return new LeaveReportRow(
                            employee.getId(),
                            employee.getFullName(),
                            employee.getEmail(),
                            balance.getLeaveType(),
                            balance.getYear(),
                            balance.getTotalDays(),
                            balance.getUsedDays(),
                            balance.getRemainingDays(),
                            pendingDays.getOrDefault(key, 0));
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public DashboardSummaryResponse dashboardSummary(UUID organizationId) {
        UUID orgId = accessControl.resolveOrganization(organizationId);
        Organization organization = organizationService.requireOrganization(orgId);
        LocalDate today = AttendanceService.todayIn(organization);

        List<UUID> visibleIds = accessControl.visibleUserIds(null);
        List<User> employees = employeesInScope(orgId, visibleIds);
        long activeEmployees = employees.size();

        List<UUID> employeeIds = employees.stream().map(User::getId).toList();
        List<AttendanceRecord> todayRecords = employeeIds.isEmpty()
                ? List.of()
                : attendanceRepository.findForReport(orgId, today, today, false, employeeIds);

        long checkedIn = todayRecords.size();
        long lateToday = todayRecords.stream().filter(AttendanceRecord::isLate).count();
        long openCheckOuts = todayRecords.stream().filter(r -> r.getCheckOutTime() == null).count();

        long onLeaveToday = employeeIds.isEmpty() ? 0 : leaveRequestRepository
                .findApprovedOverlapping(orgId, today, today, false, employeeIds)
                .stream()
                .map(lr -> lr.getEmployee().getId())
                .distinct()
                .count();

        boolean workingDay = isWorkingDay(today);
        long absentToday = workingDay
                ? Math.max(0, activeEmployees - checkedIn - onLeaveToday)
                : 0;

        long expectedToday = Math.max(0, activeEmployees - onLeaveToday);

        return new DashboardSummaryResponse(
                today,
                activeEmployees,
                checkedIn,
                lateToday,
                onLeaveToday,
                absentToday,
                workingDay ? rate((int) checkedIn, (int) expectedToday) : 0.0,
                leaveRequestRepository.countPendingByOrganization(orgId),
                openCheckOuts);
    }

    // ---------- CSV export ----------

    public String attendanceReportCsv(List<AttendanceReportRow> rows) {
        StringBuilder csv = new StringBuilder(
                "Employee,Email,Working Days,Present,Late,On Leave,Absent,"
                        + "Attendance %,Lateness %,Absenteeism %,Hours Worked\n");
        for (AttendanceReportRow r : rows) {
            csv.append(String.join(",",
                    quote(r.employeeName()), quote(r.email()),
                    String.valueOf(r.workingDays()), String.valueOf(r.presentDays()),
                    String.valueOf(r.lateDays()), String.valueOf(r.leaveDays()),
                    String.valueOf(r.absentDays()),
                    String.valueOf(r.attendanceRate()), String.valueOf(r.latenessRate()),
                    String.valueOf(r.absenteeismRate()), String.valueOf(r.totalHoursWorked())))
                    .append('\n');
        }
        return csv.toString();
    }

    public String latenessReportCsv(List<LatenessReportRow> rows) {
        StringBuilder csv = new StringBuilder(
                "Employee,Email,Check-ins,Late Check-ins,Lateness %,Avg Minutes Late\n");
        for (LatenessReportRow r : rows) {
            csv.append(String.join(",",
                    quote(r.employeeName()), quote(r.email()),
                    String.valueOf(r.checkInsRecorded()), String.valueOf(r.lateCheckIns()),
                    String.valueOf(r.latenessRate()),
                    r.averageMinutesLate() == null ? "" : String.valueOf(r.averageMinutesLate())))
                    .append('\n');
        }
        return csv.toString();
    }

    public String leaveReportCsv(List<LeaveReportRow> rows) {
        StringBuilder csv = new StringBuilder(
                "Employee,Email,Leave Type,Year,Total Days,Used Days,Remaining Days,Pending Days\n");
        for (LeaveReportRow r : rows) {
            csv.append(String.join(",",
                    quote(r.employeeName()), quote(r.email()),
                    r.leaveType().name(), String.valueOf(r.year()),
                    String.valueOf(r.totalDays()), String.valueOf(r.usedDays()),
                    String.valueOf(r.remainingDays()), String.valueOf(r.pendingDays())))
                    .append('\n');
        }
        return csv.toString();
    }

    // ---------- helpers ----------

    private record DateWindow(LocalDate from, LocalDate to) {
    }

    /** Defaults to the current month when either bound is omitted. */
    private DateWindow validateWindow(LocalDate fromDate, LocalDate toDate) {
        LocalDate today = LocalDate.now();
        LocalDate from = fromDate == null ? today.withDayOfMonth(1) : fromDate;
        LocalDate to = toDate == null ? today : toDate;

        if (to.isBefore(from)) {
            throw new BusinessRuleException("toDate must not be before fromDate");
        }
        if (java.time.temporal.ChronoUnit.DAYS.between(from, to) > MAX_REPORT_DAYS) {
            throw new BusinessRuleException(
                    "Report range may not exceed " + MAX_REPORT_DAYS + " days");
        }
        return new DateWindow(from, to);
    }

    /** Active employees in the tenant, narrowed to what the caller may see. */
    private List<User> employeesInScope(UUID organizationId, List<UUID> visibleIds) {
        List<User> active = userRepository.findActiveByOrganizationId(organizationId);
        List<User> scoped = visibleIds == null
                ? active
                : active.stream().filter(u -> visibleIds.contains(u.getId())).toList();
        return scoped.stream()
                .sorted(Comparator.comparing(User::getFullName))
                .toList();
    }

    private Map<UUID, Set<LocalDate>> approvedLeaveDays(UUID organizationId, LocalDate from,
                                                        LocalDate to, List<UUID> employeeIds) {
        Map<UUID, Set<LocalDate>> result = new HashMap<>();
        for (LeaveRequest lr : leaveRequestRepository
                .findApprovedOverlapping(organizationId, from, to, false, employeeIds)) {
            Set<LocalDate> days = result.computeIfAbsent(
                    lr.getEmployee().getId(), k -> new HashSet<>());
            LocalDate cursor = lr.getFromDate().isBefore(from) ? from : lr.getFromDate();
            LocalDate end = lr.getToDate().isAfter(to) ? to : lr.getToDate();
            while (!cursor.isAfter(end)) {
                days.add(cursor);
                cursor = cursor.plusDays(1);
            }
        }
        return result;
    }

    /** Mon-Fri. Saturday and Sunday are not counted as expected working days. */
    private static List<LocalDate> workingDaysBetween(LocalDate from, LocalDate to) {
        List<LocalDate> days = new ArrayList<>();
        for (LocalDate cursor = from; !cursor.isAfter(to); cursor = cursor.plusDays(1)) {
            if (isWorkingDay(cursor)) {
                days.add(cursor);
            }
        }
        return days;
    }

    private static boolean isWorkingDay(LocalDate date) {
        DayOfWeek dow = date.getDayOfWeek();
        return dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY;
    }

    /** Percentage to two decimals; a zero denominator yields 0 rather than NaN. */
    private static double rate(int numerator, int denominator) {
        return denominator == 0 ? 0.0 : round2(numerator * 100.0 / denominator);
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    /** Minimal RFC 4180 quoting so names containing commas survive the export. */
    private static String quote(String value) {
        if (value == null) {
            return "";
        }
        String escaped = value.replace("\"", "\"\"");
        return "\"" + escaped + "\"";
    }
}
