package com.attendance.api.domain;

import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/** Join row assigning an employee to a work location they may check in at. */
@Entity
@Table(name = "user_locations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@IdClass(UserLocation.Key.class)
public class UserLocation {

    @Id
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Id
    @Column(name = "location_id", nullable = false)
    private UUID locationId;

    @Column(name = "assigned_date", nullable = false)
    private Instant assignedDate;

    @PrePersist
    void onCreate() {
        if (assignedDate == null) {
            assignedDate = Instant.now();
        }
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {

        private static final long serialVersionUID = 1L;

        private UUID userId;
        private UUID locationId;
    }
}
