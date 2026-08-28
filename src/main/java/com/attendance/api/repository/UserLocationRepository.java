package com.attendance.api.repository;

import com.attendance.api.domain.UserLocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface UserLocationRepository extends JpaRepository<UserLocation, UserLocation.Key> {

    List<UserLocation> findByUserId(UUID userId);

    @Modifying
    @Query("DELETE FROM UserLocation ul WHERE ul.userId = :userId")
    void deleteByUserId(@Param("userId") UUID userId);
}
