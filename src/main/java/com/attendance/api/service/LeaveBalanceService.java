package com.attendance.api.service;

import com.attendance.api.domain.LeaveBalance;
import com.attendance.api.domain.User;
import com.attendance.api.domain.enums.LeaveType;
import com.attendance.api.dto.leave.AdjustLeaveBalanceRequest;
import com.attendance.api.dto.leave.LeaveBalanceResponse;
import com.attendance.api.exception.BusinessRuleException;
import com.attendance.api.repository.LeaveBalanceRepository;
import com.attendance.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Entitlement slots per user, leave type and year, plus the debit/credit used by approvals. */
@Service
@RequiredArgsConstructor
@Slf4j
public class LeaveBalanceService {

    /** Default annual entitlement applied when a user is created. */
    private static final Map<LeaveType, Integer> DEFAULT_ENTITLEMENTS = Map.of(
            LeaveType.ANNUAL, 20,
            LeaveType.SICK, 10,
            LeaveType.UNPAID, 30,
            LeaveType.BEREAVEMENT, 5,
            LeaveType.OTHER, 0);

    private final LeaveBalanceRepository leaveBalanceRepository;
    private final UserRepository userRepository;
    private final AccessControlService accessControl;

    /** Seeds this year's slots for a newly created user. Idempotent. */
    @Transactional
    public void seedDefaultBalances(User user) {
        int year = LocalDate.now().getYear();
        DEFAULT_ENTITLEMENTS.forEach((type, days) -> {
            if (leaveBalanceRepository.findByUserIdAndLeaveTypeAndYear(user.getId(), type, year).isEmpty()) {
                leaveBalanceRepository.save(LeaveBalance.builder()
                        .organization(user.getOrganization())
                        .user(user)
                        .leaveType(type)
                        .year(year)
                        .totalDays(days)
                        .usedDays(0)
                        .build());
            }
        });
        log.debug("Seeded {} leave balance slots for user {}", DEFAULT_ENTITLEMENTS.size(), user.getId());
    }

    @Transactional(readOnly = true)
    public List<LeaveBalanceResponse> listForUser(UUID userId, Integer year) {
        // Role visibility alone is not enough: an org admin is allowed to read "any user",
        // so the target must also be confirmed to live in the caller's own tenant.
        accessControl.loadUserInOrganization(userId, accessControl.currentOrganization());
        accessControl.requireCanViewUser(userId);
        int targetYear = year == null ? LocalDate.now().getYear() : year;
        return leaveBalanceRepository.findByUserIdAndYearOrderByLeaveType(userId, targetYear)
                .stream()
                .map(LeaveBalanceResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<LeaveBalanceResponse> listForCurrentUser(Integer year) {
        return listForUser(com.attendance.api.security.SecurityUtils.currentUserId(), year);
    }

    /** Org-admin correction of an entitlement slot; creates it when absent. */
    @Transactional
    public LeaveBalanceResponse adjust(UUID organizationId, UUID userId, LeaveType leaveType,
                                       Integer year, AdjustLeaveBalanceRequest request) {
        UUID orgId = accessControl.resolveOrganization(organizationId);
        User user = accessControl.loadUserInOrganization(userId, orgId);
        int targetYear = year == null ? LocalDate.now().getYear() : year;

        LeaveBalance balance = leaveBalanceRepository
                .findByUserIdAndLeaveTypeAndYear(userId, leaveType, targetYear)
                .orElseGet(() -> LeaveBalance.builder()
                        .organization(user.getOrganization())
                        .user(user)
                        .leaveType(leaveType)
                        .year(targetYear)
                        .totalDays(0)
                        .usedDays(0)
                        .build());

        balance.setTotalDays(request.totalDays());
        if (request.usedDays() != null) {
            balance.setUsedDays(request.usedDays());
        }
        if (balance.getUsedDays() > balance.getTotalDays()) {
            throw new BusinessRuleException(
                    "Used days (" + balance.getUsedDays() + ") cannot exceed total days ("
                            + balance.getTotalDays() + ")");
        }

        LeaveBalance saved = leaveBalanceRepository.save(balance);
        log.info("Leave balance adjusted: user={} type={} year={} total={} used={}",
                userId, leaveType, targetYear, saved.getTotalDays(), saved.getUsedDays());
        return LeaveBalanceResponse.from(saved);
    }

    /**
     * Debits an approved request against the matching slot.
     * UNPAID and OTHER are tracked but never blocked on insufficient balance.
     */
    @Transactional
    public void debit(User employee, LeaveType leaveType, int year, int days) {
        LeaveBalance balance = leaveBalanceRepository
                .findByUserIdAndLeaveTypeAndYear(employee.getId(), leaveType, year)
                .orElseGet(() -> leaveBalanceRepository.save(LeaveBalance.builder()
                        .organization(employee.getOrganization())
                        .user(employee)
                        .leaveType(leaveType)
                        .year(year)
                        .totalDays(DEFAULT_ENTITLEMENTS.getOrDefault(leaveType, 0))
                        .usedDays(0)
                        .build()));

        int remaining = balance.getRemainingDays();
        if (isCapped(leaveType) && days > remaining) {
            throw new BusinessRuleException(String.format(
                    "Insufficient %s balance: %d day(s) requested but only %d remaining for %d",
                    leaveType, days, remaining, year));
        }

        balance.setUsedDays(balance.getUsedDays() + days);
        leaveBalanceRepository.save(balance);
        log.info("Debited {} {} day(s) from user {} ({} remaining)",
                days, leaveType, employee.getId(), balance.getRemainingDays());
    }

    /** Returns days to the slot, used when an approved request is later cancelled. */
    @Transactional
    public void credit(User employee, LeaveType leaveType, int year, int days) {
        leaveBalanceRepository.findByUserIdAndLeaveTypeAndYear(employee.getId(), leaveType, year)
                .ifPresent(balance -> {
                    balance.setUsedDays(Math.max(0, balance.getUsedDays() - days));
                    leaveBalanceRepository.save(balance);
                    log.info("Credited {} {} day(s) back to user {}", days, leaveType, employee.getId());
                });
    }

    /** Pre-flight check at submission time so an employee learns early, not at approval. */
    @Transactional(readOnly = true)
    public void assertSufficientBalance(UUID userId, LeaveType leaveType, int year, int days) {
        if (!isCapped(leaveType)) {
            return;
        }
        int remaining = leaveBalanceRepository
                .findByUserIdAndLeaveTypeAndYear(userId, leaveType, year)
                .map(LeaveBalance::getRemainingDays)
                .orElse(DEFAULT_ENTITLEMENTS.getOrDefault(leaveType, 0));

        if (days > remaining) {
            throw new BusinessRuleException(String.format(
                    "Insufficient %s balance: %d day(s) requested but only %d remaining for %d",
                    leaveType, days, remaining, year));
        }
    }

    /** Ensures a user has slots for the given year, e.g. at annual rollover. */
    @Transactional
    public int rolloverYear(UUID organizationId, int year) {
        UUID orgId = accessControl.resolveOrganization(organizationId);
        List<User> users = userRepository.findActiveByOrganizationId(orgId);
        int created = 0;

        for (User user : users) {
            for (Map.Entry<LeaveType, Integer> entry : DEFAULT_ENTITLEMENTS.entrySet()) {
                boolean exists = leaveBalanceRepository
                        .findByUserIdAndLeaveTypeAndYear(user.getId(), entry.getKey(), year)
                        .isPresent();
                if (!exists) {
                    leaveBalanceRepository.save(LeaveBalance.builder()
                            .organization(user.getOrganization())
                            .user(user)
                            .leaveType(entry.getKey())
                            .year(year)
                            .totalDays(entry.getValue())
                            .usedDays(0)
                            .build());
                    created++;
                }
            }
        }
        log.info("Rollover for org {} year {} created {} balance slots", orgId, year, created);
        return created;
    }

    /** UNPAID and OTHER are informational; the rest are enforced against entitlement. */
    private static boolean isCapped(LeaveType type) {
        return type != LeaveType.UNPAID && type != LeaveType.OTHER;
    }
}
