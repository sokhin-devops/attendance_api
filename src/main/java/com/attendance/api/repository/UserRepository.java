package com.attendance.api.repository;

import com.attendance.api.domain.User;
import com.attendance.api.domain.enums.Role;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    /** Tenant-scoped lookup; the pairing of org + email is the login identity. */
    @Query("""
           SELECT u FROM User u
           WHERE LOWER(u.email) = LOWER(:email)
             AND u.organization.id = :organizationId
           """)
    Optional<User> findByEmailAndOrganizationId(@Param("email") String email,
                                               @Param("organizationId") UUID organizationId);

    /** Platform-level users only (super admins carry no organization). */
    @Query("SELECT u FROM User u WHERE LOWER(u.email) = LOWER(:email) AND u.organization IS NULL")
    Optional<User> findPlatformUserByEmail(@Param("email") String email);

    /** Used by login when no tenant key was supplied: unique hit means unambiguous. */
    @Query("SELECT u FROM User u WHERE LOWER(u.email) = LOWER(:email)")
    List<User> findAllByEmail(@Param("email") String email);

    Optional<User> findByIdAndOrganizationId(UUID id, UUID organizationId);

    @Query("""
           SELECT u FROM User u
           WHERE u.organization.id = :organizationId
             AND (:anyRole = TRUE OR u.role = :role)
             AND (:anyActive = TRUE OR u.active = :active)
             AND (LOWER(u.email) LIKE :pattern
                  OR LOWER(u.firstName) LIKE :pattern
                  OR LOWER(u.lastName) LIKE :pattern)
           """)
    Page<User> searchInOrganization(@Param("organizationId") UUID organizationId,
                                   @Param("anyRole") boolean anyRole,
                                   @Param("role") Role role,
                                   @Param("anyActive") boolean anyActive,
                                   @Param("active") boolean active,
                                   @Param("pattern") String pattern,
                                   Pageable pageable);

    List<User> findByManagerId(UUID managerId);

    @Query("SELECT u.id FROM User u WHERE u.manager.id = :managerId")
    List<UUID> findIdsByManagerId(@Param("managerId") UUID managerId);

    @Query("SELECT u FROM User u WHERE u.organization.id = :organizationId AND u.active = true")
    List<User> findActiveByOrganizationId(@Param("organizationId") UUID organizationId);

    @Query("SELECT COUNT(u) FROM User u WHERE u.organization.id = :organizationId AND u.active = true")
    long countActiveByOrganizationId(@Param("organizationId") UUID organizationId);

    @Query("""
           SELECT u FROM User u
           WHERE u.organization.id = :organizationId
             AND u.role IN :roles
             AND u.active = true
           """)
    List<User> findByOrganizationIdAndRoleIn(@Param("organizationId") UUID organizationId,
                                             @Param("roles") List<Role> roles);
}
