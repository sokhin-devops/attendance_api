package com.attendance.api.service;

import com.attendance.api.domain.LeaveRequest;
import com.attendance.api.domain.Organization;
import com.attendance.api.domain.User;
import com.attendance.api.domain.enums.LeaveStatus;
import com.attendance.api.domain.enums.NotificationType;
import com.attendance.api.domain.enums.Role;
import com.attendance.api.dto.common.PageResponse;
import com.attendance.api.dto.leave.CreateLeaveRequest;
import com.attendance.api.dto.leave.LeaveDecisionRequest;
import com.attendance.api.dto.leave.LeaveRequestResponse;
import com.attendance.api.exception.AccessDeniedBusinessException;
import com.attendance.api.exception.BusinessRuleException;
import com.attendance.api.exception.ConflictException;
import com.attendance.api.exception.Require;
import com.attendance.api.repository.LeaveRequestRepository;
import com.attendance.api.repository.QueryParams;
import com.attendance.api.repository.UserRepository;
import com.attendance.api.security.SecurityUtils;
import com.attendance.api.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.lang.NonNull;

/**
 * Leave request lifecycle: submit, approve, reject, cancel.
 *
 * <p>Approval routing follows the spec: a request goes to the employee's manager when they
 * have one, and an org admin can decide on anything in their tenant. Balance is debited at
 * approval time, and credited back if an approved request is later cancelled.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LeaveService {

    private final LeaveRequestRepository leaveRequestRepository;
    private final UserRepository userRepository;
    private final LeaveBalanceService leaveBalanceService;
    private final NotificationService notificationService;
    private final AccessControlService accessControl;

    @Transactional
    public LeaveRequestResponse submit(CreateLeaveRequest request) {
        UserPrincipal principal = SecurityUtils.requirePrincipal();
        UUID orgId = accessControl.currentOrganization();

        User employee = resolveEmployee(request.employeeId(), principal, orgId);
        User requester = requireUser(principal.getId());
        Organization organization = employee.getOrganization();

        if (request.toDate().isBefore(request.fromDate())) {
            throw new BusinessRuleException("toDate must not be before fromDate");
        }

        int days = inclusiveDays(request.fromDate(), request.toDate());
        if (leaveRequestRepository.countOverlapping(
                employee.getId(), request.fromDate(), request.toDate()) > 0) {
            throw new ConflictException(
                    "A pending or approved leave request already covers part of that range");
        }

        // Fail early rather than at approval time so the employee can adjust the request.
        leaveBalanceService.assertSufficientBalance(
                employee.getId(), request.leaveType(), request.fromDate().getYear(), days);

        LeaveRequest leaveRequest = leaveRequestRepository.save(LeaveRequest.builder()
                .organization(organization)
                .employee(employee)
                .leaveType(request.leaveType())
                .fromDate(request.fromDate())
                .toDate(request.toDate())
                .daysRequested(days)
                .reason(request.reason())
                .status(LeaveStatus.PENDING)
                .requestedBy(requester)
                .build());

        log.info("Leave request {} submitted: employee={} type={} {}..{} ({} day(s))",
                leaveRequest.getId(), employee.getId(), request.leaveType(),
                request.fromDate(), request.toDate(), days);

        notifyApprovers(employee, organization, leaveRequest);
        return LeaveRequestResponse.from(leaveRequest);
    }

    @Transactional(readOnly = true)
    public PageResponse<LeaveRequestResponse> list(UUID employeeId, LeaveStatus status,
                                                   LocalDate fromDate, LocalDate toDate,
                                                   Pageable pageable) {
        UUID orgId = accessControl.currentOrganization();
        List<UUID> visibleIds = accessControl.visibleUserIds(employeeId);

        return PageResponse.of(
                leaveRequestRepository.search(orgId,
                        QueryParams.isUnrestricted(visibleIds),
                        QueryParams.orPlaceholder(visibleIds),
                        status == null, status,
                        QueryParams.fromOrMin(fromDate), QueryParams.toOrMax(toDate),
                        pageable),
                LeaveRequestResponse::from);
    }

    @Transactional(readOnly = true)
    public PageResponse<LeaveRequestResponse> myRequests(LeaveStatus status, Pageable pageable) {
        return list(SecurityUtils.currentUserId(), status, null, null, pageable);
    }

    @Transactional(readOnly = true)
    public LeaveRequestResponse get(UUID requestId) {
        LeaveRequest leaveRequest = requireRequest(requestId);
        accessControl.requireCanViewUser(leaveRequest.getEmployee().getId());
        return LeaveRequestResponse.from(leaveRequest);
    }

    @Transactional
    public LeaveRequestResponse approve(UUID requestId, LeaveDecisionRequest decision) {
        LeaveRequest leaveRequest = requireRequest(requestId);
        User approver = requireUser(SecurityUtils.currentUserId());

        accessControl.requireCanDecideFor(leaveRequest.getEmployee());
        requirePending(leaveRequest);

        // Debit first: an insufficient balance must abort the approval, not follow it.
        leaveBalanceService.debit(
                leaveRequest.getEmployee(),
                leaveRequest.getLeaveType(),
                leaveRequest.getFromDate().getYear(),
                leaveRequest.getDaysRequested());

        leaveRequest.setStatus(LeaveStatus.APPROVED);
        leaveRequest.setApprovedBy(approver);
        leaveRequest.setDecisionNote(decision == null ? null : decision.note());
        leaveRequest.setDecidedAt(Instant.now());

        LeaveRequest saved = leaveRequestRepository.save(leaveRequest);
        log.info("Leave request {} approved by {}", requestId, approver.getId());

        notificationService.notify(saved.getEmployee(), saved.getOrganization(),
                NotificationType.LEAVE_APPROVED,
                "Leave approved",
                String.format("Your %s leave for %s to %s was approved by %s.",
                        saved.getLeaveType(), saved.getFromDate(), saved.getToDate(),
                        approver.getFullName()),
                saved.getId());

        return LeaveRequestResponse.from(saved);
    }

    @Transactional
    public LeaveRequestResponse reject(UUID requestId, LeaveDecisionRequest decision) {
        LeaveRequest leaveRequest = requireRequest(requestId);
        User approver = requireUser(SecurityUtils.currentUserId());

        accessControl.requireCanDecideFor(leaveRequest.getEmployee());
        requirePending(leaveRequest);

        leaveRequest.setStatus(LeaveStatus.REJECTED);
        leaveRequest.setApprovedBy(approver);
        leaveRequest.setDecisionNote(decision == null ? null : decision.note());
        leaveRequest.setDecidedAt(Instant.now());

        LeaveRequest saved = leaveRequestRepository.save(leaveRequest);
        log.info("Leave request {} rejected by {}", requestId, approver.getId());

        String note = saved.getDecisionNote() == null ? "" : " Note: " + saved.getDecisionNote();
        notificationService.notify(saved.getEmployee(), saved.getOrganization(),
                NotificationType.LEAVE_REJECTED,
                "Leave rejected",
                String.format("Your %s leave for %s to %s was rejected by %s.%s",
                        saved.getLeaveType(), saved.getFromDate(), saved.getToDate(),
                        approver.getFullName(), note),
                saved.getId());

        return LeaveRequestResponse.from(saved);
    }

    /**
     * Cancellation. An employee may withdraw their own pending request; an org admin may
     * also cancel an already-approved one, which credits the days back to the balance.
     */
    @Transactional
    public LeaveRequestResponse cancel(UUID requestId) {
        LeaveRequest leaveRequest = requireRequest(requestId);
        UserPrincipal principal = SecurityUtils.requirePrincipal();

        boolean isOwner = principal.getId().equals(leaveRequest.getEmployee().getId());
        boolean isAdmin = principal.isOrgAdmin();

        if (!isOwner && !isAdmin) {
            throw new AccessDeniedBusinessException(
                    "Only the requesting employee or an organization admin may cancel this request");
        }

        switch (leaveRequest.getStatus()) {
            case PENDING -> { /* always cancellable */ }
            case APPROVED -> {
                if (!isAdmin) {
                    throw new BusinessRuleException(
                            "This request is already approved; ask your admin to cancel it");
                }
                leaveBalanceService.credit(
                        leaveRequest.getEmployee(),
                        leaveRequest.getLeaveType(),
                        leaveRequest.getFromDate().getYear(),
                        leaveRequest.getDaysRequested());
            }
            default -> throw new BusinessRuleException(
                    "A " + leaveRequest.getStatus() + " request cannot be cancelled");
        }

        leaveRequest.setStatus(LeaveStatus.CANCELLED);
        leaveRequest.setDecidedAt(Instant.now());
        LeaveRequest saved = leaveRequestRepository.save(leaveRequest);

        log.info("Leave request {} cancelled by {}", requestId, principal.getId());
        return LeaveRequestResponse.from(saved);
    }

    // ---------- helpers ----------

    /** Employees file for themselves; managers and admins may file on behalf of others. */
    private User resolveEmployee(UUID requestedEmployeeId, UserPrincipal principal, UUID orgId) {
        if (requestedEmployeeId == null || requestedEmployeeId.equals(principal.getId())) {
            return requireUser(principal.getId());
        }
        if (principal.isEmployee()) {
            throw new AccessDeniedBusinessException(
                    "You may only submit leave requests for yourself");
        }
        // Managers are limited to their own team; visibleUserIds enforces that.
        accessControl.requireCanViewUser(requestedEmployeeId);
        return accessControl.loadUserInOrganization(requestedEmployeeId, orgId);
    }

    private void requirePending(LeaveRequest leaveRequest) {
        if (leaveRequest.getStatus() != LeaveStatus.PENDING) {
            throw new BusinessRuleException(
                    "This request is already " + leaveRequest.getStatus() + " and cannot be decided again");
        }
    }

    /**
     * Routes the new request to whoever can act on it: the employee's manager if they have
     * one, otherwise every org admin.
     */
    private void notifyApprovers(User employee, Organization organization, LeaveRequest leaveRequest) {
        List<User> approvers = new ArrayList<>();

        User manager = employee.getManager();
        if (manager != null && manager.isActive()) {
            approvers.add(manager);
        } else {
            approvers.addAll(userRepository.findByOrganizationIdAndRoleIn(
                    organization.getId(), List.of(Role.ORG_ADMIN)));
        }

        approvers.removeIf(a -> a.getId().equals(employee.getId()));
        if (approvers.isEmpty()) {
            log.warn("Leave request {} has no eligible approver in org {}",
                    leaveRequest.getId(), organization.getId());
            return;
        }

        String title = "Leave request awaiting approval";
        String message = String.format("%s requested %d day(s) of %s leave (%s to %s).",
                employee.getFullName(), leaveRequest.getDaysRequested(), leaveRequest.getLeaveType(),
                leaveRequest.getFromDate(), leaveRequest.getToDate());

        notificationService.notifyAll(approvers, organization,
                NotificationType.LEAVE_REQUEST_SUBMITTED, title, message, leaveRequest.getId());
    }

    private LeaveRequest requireRequest(UUID requestId) {
        UUID orgId = accessControl.currentOrganization();
        return Require.found(
                leaveRequestRepository.findByIdAndOrganizationId(requestId, orgId),
                "Leave request", requestId);
    }

    @NonNull
    private User requireUser(@NonNull UUID userId) {
        return Require.found(userRepository.findById(userId), "User", userId);
    }

    /** Inclusive calendar-day span, e.g. Mon..Wed is 3 days. */
    static int inclusiveDays(LocalDate from, LocalDate to) {
        return (int) ChronoUnit.DAYS.between(from, to) + 1;
    }
}
