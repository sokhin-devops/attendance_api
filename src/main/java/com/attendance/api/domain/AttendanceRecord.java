package com.attendance.api.domain;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.lang.Nullable;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** One row per user per calendar day, holding the check-in and its matching check-out. */
@Entity
@Table(name = "attendance_records")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AttendanceRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Nullable
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "location_id")
    private Location location;

    /** Calendar day in the organization timezone; the natural key alongside user. */
    @Column(name = "work_date", nullable = false)
    private LocalDate workDate;

    @Column(name = "check_in_time", nullable = false)
    private Instant checkInTime;

    @Column(name = "check_out_time")
    private Instant checkOutTime;

    @Column(name = "check_in_latitude")
    private Double checkInLatitude;

    @Column(name = "check_in_longitude")
    private Double checkInLongitude;

    @Column(name = "check_out_latitude")
    private Double checkOutLatitude;

    @Column(name = "check_out_longitude")
    private Double checkOutLongitude;

    @Column(name = "gps_accuracy_meters")
    private Double gpsAccuracyMeters;

    @Column(name = "is_late", nullable = false)
    @Builder.Default
    private boolean late = false;

    @Column(name = "is_manual_override", nullable = false)
    @Builder.Default
    private boolean manualOverride = false;

    @Column(name = "override_reason", length = 500)
    private String overrideReason;

    @Nullable
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "overridden_by_id")
    private User overriddenBy;

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
