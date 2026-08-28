package com.attendance.api.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "organizations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Organization {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    /** Stable lowercase identifier used to disambiguate logins across tenants. */
    @Column(name = "tenant_key", nullable = false, length = 60)
    private String tenantKey;

    @Column(name = "timezone", nullable = false, length = 64)
    @Builder.Default
    private String timezone = "UTC";

    @Column(name = "work_start_hour", nullable = false)
    @Builder.Default
    private Integer workStartHour = 9;

    @Column(name = "work_end_hour", nullable = false)
    @Builder.Default
    private Integer workEndHour = 17;

    /** When true, employees may check in outside a geofence with a written reason. */
    @Column(name = "allow_manual_check_in", nullable = false)
    @Builder.Default
    private boolean allowManualCheckIn = false;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
