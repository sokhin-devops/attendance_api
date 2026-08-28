package com.attendance.api.repository;

import com.attendance.api.domain.LeaveBalance;
import com.attendance.api.domain.enums.LeaveType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LeaveBalanceRepository extends JpaRepository<LeaveBalance, UUID> {

    Optional<LeaveBalance> findByUserIdAndLeaveTypeAndYear(UUID userId, LeaveType leaveType, Integer year);

    List<LeaveBalance> findByUserIdAndYearOrderByLeaveType(UUID userId, Integer year);

    @Query("""
           SELECT lb FROM LeaveBalance lb
           WHERE lb.organization.id = :organizationId
             AND lb.year = :year
             AND (:allUsers = TRUE OR lb.user.id IN :userIds)
           """)
    List<LeaveBalance> findForReport(@Param("organizationId") UUID organizationId,
                                    @Param("year") Integer year,
                                    @Param("allUsers") boolean allUsers,
                                    @Param("userIds") List<UUID> userIds);
}
