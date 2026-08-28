package com.attendance.api.repository;

import com.attendance.api.domain.LeaveRequest;
import com.attendance.api.domain.enums.LeaveStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LeaveRequestRepository extends JpaRepository<LeaveRequest, UUID> {

    Optional<LeaveRequest> findByIdAndOrganizationId(UUID id, UUID organizationId);

    /**
     * Paged search. Optional filters arrive pre-normalised by {@link QueryParams}:
     * {@code allUsers} true means org-wide, {@code anyStatus} true ignores {@code status},
     * and the date bounds are always concrete. Date matching is overlap-based: a request is
     * returned when any of its days fall inside the window.
     */
    @Query("""
           SELECT lr FROM LeaveRequest lr
           WHERE lr.organization.id = :organizationId
             AND (:allUsers = TRUE OR lr.employee.id IN :employeeIds)
             AND (:anyStatus = TRUE OR lr.status = :status)
             AND lr.toDate >= :fromDate
             AND lr.fromDate <= :toDate
           """)
    Page<LeaveRequest> search(@Param("organizationId") UUID organizationId,
                              @Param("allUsers") boolean allUsers,
                              @Param("employeeIds") List<UUID> employeeIds,
                              @Param("anyStatus") boolean anyStatus,
                              @Param("status") LeaveStatus status,
                              @Param("fromDate") LocalDate fromDate,
                              @Param("toDate") LocalDate toDate,
                              Pageable pageable);

    /** Approved leave overlapping a date range - drives the ON_LEAVE report status. */
    @Query("""
           SELECT lr FROM LeaveRequest lr
           WHERE lr.organization.id = :organizationId
             AND lr.status = com.attendance.api.domain.enums.LeaveStatus.APPROVED
             AND lr.fromDate <= :toDate
             AND lr.toDate >= :fromDate
             AND (:allUsers = TRUE OR lr.employee.id IN :userIds)
           """)
    List<LeaveRequest> findApprovedOverlapping(@Param("organizationId") UUID organizationId,
                                               @Param("fromDate") LocalDate fromDate,
                                               @Param("toDate") LocalDate toDate,
                                               @Param("allUsers") boolean allUsers,
                                               @Param("userIds") List<UUID> userIds);

    /** Overlap guard: an employee may not hold two live requests for the same days. */
    @Query("""
           SELECT COUNT(lr) FROM LeaveRequest lr
           WHERE lr.employee.id = :employeeId
             AND lr.status IN (com.attendance.api.domain.enums.LeaveStatus.PENDING,
                               com.attendance.api.domain.enums.LeaveStatus.APPROVED)
             AND lr.fromDate <= :toDate
             AND lr.toDate >= :fromDate
           """)
    long countOverlapping(@Param("employeeId") UUID employeeId,
                          @Param("fromDate") LocalDate fromDate,
                          @Param("toDate") LocalDate toDate);

    @Query("""
           SELECT COUNT(lr) FROM LeaveRequest lr
           WHERE lr.organization.id = :organizationId
             AND lr.status = com.attendance.api.domain.enums.LeaveStatus.PENDING
           """)
    long countPendingByOrganization(@Param("organizationId") UUID organizationId);
}
