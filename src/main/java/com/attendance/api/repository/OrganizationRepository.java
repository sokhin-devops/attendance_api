package com.attendance.api.repository;

import com.attendance.api.domain.Organization;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface OrganizationRepository extends JpaRepository<Organization, UUID> {

    Optional<Organization> findByTenantKeyIgnoreCase(String tenantKey);

    boolean existsByTenantKeyIgnoreCase(String tenantKey);

    boolean existsByNameIgnoreCase(String name);

    /** @param pattern lowercase LIKE pattern from {@link QueryParams#likePattern} */
    @Query("""
           SELECT o FROM Organization o
           WHERE LOWER(o.name) LIKE :pattern
              OR LOWER(o.tenantKey) LIKE :pattern
           """)
    Page<Organization> search(@Param("pattern") String pattern, Pageable pageable);
}
