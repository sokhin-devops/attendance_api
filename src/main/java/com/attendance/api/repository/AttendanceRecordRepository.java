package com.attendance.api.repository;

import com.attendance.api.domain.AttendanceRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AttendanceRecordRepository extends JpaRepository<AttendanceRecord, UUID> {

    Optional<AttendanceRecord> findByUserIdAndWorkDate(UUID userId, LocalDate workDate);

    Optional<AttendanceRecord> findByIdAndOrganizationId(UUID id, UUID organizationId);

    /**
     * Paged history. Optional filters arrive pre-normalised by {@link QueryParams}:
     * {@code allUsers} true means org-wide (for admins), otherwise the result is limited to
     * {@code userIds}; {@code anyLocation} true ignores {@code locationId}; and the date
     * bounds are always concrete, widened to the sentinel range when the caller omits them.
     */
    @Query("""
           SELECT a FROM AttendanceRecord a
           WHERE a.organization.id = :organizationId
             AND (:allUsers = TRUE OR a.user.id IN :userIds)
             AND (:anyLocation = TRUE OR a.location.id = :locationId)
             AND a.workDate BETWEEN :fromDate AND :toDate
           """)
    Page<AttendanceRecord> search(@Param("organizationId") UUID organizationId,
                                 @Param("allUsers") boolean allUsers,
                                 @Param("userIds") List<UUID> userIds,
                                 @Param("anyLocation") boolean anyLocation,
                                 @Param("locationId") UUID locationId,
                                 @Param("fromDate") LocalDate fromDate,
                                 @Param("toDate") LocalDate toDate,
                                 Pageable pageable);

    @Query("""
           SELECT a FROM AttendanceRecord a
           WHERE a.organization.id = :organizationId
             AND a.workDate BETWEEN :fromDate AND :toDate
             AND (:allUsers = TRUE OR a.user.id IN :userIds)
           """)
    List<AttendanceRecord> findForReport(@Param("organizationId") UUID organizationId,
                                        @Param("fromDate") LocalDate fromDate,
                                        @Param("toDate") LocalDate toDate,
                                        @Param("allUsers") boolean allUsers,
                                        @Param("userIds") List<UUID> userIds);

    /** Open check-ins used by the end-of-day check-out reminder job. */
    @Query("""
           SELECT a FROM AttendanceRecord a
           WHERE a.workDate = :workDate AND a.checkOutTime IS NULL
           """)
    List<AttendanceRecord> findOpenByWorkDate(@Param("workDate") LocalDate workDate);
}
