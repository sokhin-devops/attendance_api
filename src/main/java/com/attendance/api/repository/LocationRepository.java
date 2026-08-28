package com.attendance.api.repository;

import com.attendance.api.domain.Location;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LocationRepository extends JpaRepository<Location, UUID> {

    Optional<Location> findByIdAndOrganizationId(UUID id, UUID organizationId);

    boolean existsByOrganizationIdAndNameIgnoreCase(UUID organizationId, String name);

    @Query("""
           SELECT l FROM Location l
           WHERE l.organization.id = :organizationId
             AND (:anyActive = TRUE OR l.active = :active)
             AND LOWER(l.name) LIKE :pattern
           """)
    Page<Location> searchInOrganization(@Param("organizationId") UUID organizationId,
                                       @Param("anyActive") boolean anyActive,
                                       @Param("active") boolean active,
                                       @Param("pattern") String pattern,
                                       Pageable pageable);

    /** Locations the given user is assigned to, joined through user_locations. */
    @Query(value = """
           SELECT l.* FROM locations l
           JOIN user_locations ul ON ul.location_id = l.id
           WHERE ul.user_id = :userId AND l.is_active = TRUE
           ORDER BY l.name
           """, nativeQuery = true)
    List<Location> findAssignedToUser(@Param("userId") UUID userId);
}
