package com.attendance.api.repository;

import com.attendance.api.domain.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    Optional<Notification> findByIdAndUserId(UUID id, UUID userId);

    @Query("""
           SELECT n FROM Notification n
           WHERE n.user.id = :userId
             AND (:unreadOnly = false OR n.read = false)
           """)
    Page<Notification> findForUser(@Param("userId") UUID userId,
                                  @Param("unreadOnly") boolean unreadOnly,
                                  Pageable pageable);

    long countByUserIdAndReadFalse(UUID userId);

    @Modifying
    @Query("UPDATE Notification n SET n.read = true WHERE n.user.id = :userId AND n.read = false")
    int markAllReadForUser(@Param("userId") UUID userId);

    /** Retention sweep: drops notices older than the configured window. */
    @Modifying
    @Query("DELETE FROM Notification n WHERE n.createdAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
