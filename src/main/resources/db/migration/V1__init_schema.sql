-- =====================================================================
-- V1: Core schema for the multi-tenant attendance platform.
-- Every tenant-scoped table carries organization_id and indexes it.
-- =====================================================================

CREATE TABLE organizations (
    id                      UUID         PRIMARY KEY,
    name                    VARCHAR(150) NOT NULL,
    tenant_key              VARCHAR(60)  NOT NULL,
    timezone                VARCHAR(64)  NOT NULL DEFAULT 'UTC',
    work_start_hour         INTEGER      NOT NULL DEFAULT 9,
    work_end_hour           INTEGER      NOT NULL DEFAULT 17,
    allow_manual_check_in   BOOLEAN      NOT NULL DEFAULT FALSE,
    is_active               BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_org_name        UNIQUE (name),
    CONSTRAINT uq_org_tenant_key  UNIQUE (tenant_key),
    CONSTRAINT ck_org_work_hours  CHECK (work_start_hour BETWEEN 0 AND 23
                                     AND work_end_hour   BETWEEN 0 AND 23)
);

CREATE TABLE users (
    id              UUID         PRIMARY KEY,
    organization_id UUID         NULL,
    email           VARCHAR(255) NOT NULL,
    password_hash   VARCHAR(100) NOT NULL,
    first_name      VARCHAR(80)  NOT NULL,
    last_name       VARCHAR(80)  NOT NULL,
    role            VARCHAR(20)  NOT NULL,
    manager_id      UUID         NULL,
    is_active       BOOLEAN      NOT NULL DEFAULT TRUE,
    last_login_at   TIMESTAMPTZ  NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_user_org     FOREIGN KEY (organization_id) REFERENCES organizations (id) ON DELETE CASCADE,
    CONSTRAINT fk_user_manager FOREIGN KEY (manager_id)      REFERENCES users (id)         ON DELETE SET NULL,
    CONSTRAINT ck_user_role    CHECK (role IN ('SUPER_ADMIN', 'ORG_ADMIN', 'MANAGER', 'EMPLOYEE')),
    -- SUPER_ADMIN rows are platform-level and carry a NULL organization_id.
    CONSTRAINT ck_user_org_scope CHECK (
        (role = 'SUPER_ADMIN' AND organization_id IS NULL)
        OR (role <> 'SUPER_ADMIN' AND organization_id IS NOT NULL)
    )
);

-- Email is unique per organization; platform super admins are unique globally.
CREATE UNIQUE INDEX uq_user_email_per_org
    ON users (organization_id, LOWER(email))
    WHERE organization_id IS NOT NULL;
CREATE UNIQUE INDEX uq_user_email_platform
    ON users (LOWER(email))
    WHERE organization_id IS NULL;
CREATE INDEX ix_users_org     ON users (organization_id);
CREATE INDEX ix_users_manager ON users (manager_id);
CREATE INDEX ix_users_email   ON users (LOWER(email));

CREATE TABLE locations (
    id                      UUID             PRIMARY KEY,
    organization_id         UUID             NOT NULL,
    name                    VARCHAR(150)     NOT NULL,
    address                 VARCHAR(400)     NULL,
    latitude                DOUBLE PRECISION NOT NULL,
    longitude               DOUBLE PRECISION NOT NULL,
    geofence_radius_meters  INTEGER          NOT NULL DEFAULT 100,
    is_active               BOOLEAN          NOT NULL DEFAULT TRUE,
    created_at              TIMESTAMPTZ      NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ      NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_location_org  FOREIGN KEY (organization_id) REFERENCES organizations (id) ON DELETE CASCADE,
    CONSTRAINT uq_location_name UNIQUE (organization_id, name),
    CONSTRAINT ck_location_lat    CHECK (latitude  BETWEEN -90  AND 90),
    CONSTRAINT ck_location_lon    CHECK (longitude BETWEEN -180 AND 180),
    CONSTRAINT ck_location_radius CHECK (geofence_radius_meters > 0)
);
CREATE INDEX ix_locations_org ON locations (organization_id);

CREATE TABLE user_locations (
    user_id       UUID        NOT NULL,
    location_id   UUID        NOT NULL,
    assigned_date TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, location_id),
    CONSTRAINT fk_ul_user     FOREIGN KEY (user_id)     REFERENCES users (id)     ON DELETE CASCADE,
    CONSTRAINT fk_ul_location FOREIGN KEY (location_id) REFERENCES locations (id) ON DELETE CASCADE
);
CREATE INDEX ix_ul_location ON user_locations (location_id);

CREATE TABLE attendance_records (
    id                   UUID             PRIMARY KEY,
    organization_id      UUID             NOT NULL,
    user_id              UUID             NOT NULL,
    location_id          UUID             NULL,
    work_date            DATE             NOT NULL,
    check_in_time        TIMESTAMPTZ      NOT NULL,
    check_out_time       TIMESTAMPTZ      NULL,
    check_in_latitude    DOUBLE PRECISION NULL,
    check_in_longitude   DOUBLE PRECISION NULL,
    check_out_latitude   DOUBLE PRECISION NULL,
    check_out_longitude  DOUBLE PRECISION NULL,
    gps_accuracy_meters  DOUBLE PRECISION NULL,
    is_late              BOOLEAN          NOT NULL DEFAULT FALSE,
    is_manual_override   BOOLEAN          NOT NULL DEFAULT FALSE,
    override_reason      VARCHAR(500)     NULL,
    overridden_by_id     UUID             NULL,
    created_at           TIMESTAMPTZ      NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ      NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_att_org      FOREIGN KEY (organization_id)  REFERENCES organizations (id) ON DELETE CASCADE,
    CONSTRAINT fk_att_user     FOREIGN KEY (user_id)          REFERENCES users (id)         ON DELETE CASCADE,
    CONSTRAINT fk_att_location FOREIGN KEY (location_id)      REFERENCES locations (id)     ON DELETE SET NULL,
    CONSTRAINT fk_att_override FOREIGN KEY (overridden_by_id) REFERENCES users (id)         ON DELETE SET NULL,
    CONSTRAINT ck_att_times    CHECK (check_out_time IS NULL OR check_out_time >= check_in_time),
    -- One attendance record per user per calendar day.
    CONSTRAINT uq_att_user_day UNIQUE (user_id, work_date)
);
CREATE INDEX ix_att_org       ON attendance_records (organization_id);
CREATE INDEX ix_att_user_date ON attendance_records (user_id, work_date);
CREATE INDEX ix_att_org_date  ON attendance_records (organization_id, work_date);
CREATE INDEX ix_att_created   ON attendance_records (created_at);

CREATE TABLE leave_requests (
    id               UUID          PRIMARY KEY,
    organization_id  UUID          NOT NULL,
    employee_id      UUID          NOT NULL,
    leave_type       VARCHAR(20)   NOT NULL,
    from_date        DATE          NOT NULL,
    to_date          DATE          NOT NULL,
    days_requested   INTEGER       NOT NULL,
    reason           VARCHAR(1000) NULL,
    status           VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    requested_by_id  UUID          NOT NULL,
    approved_by_id   UUID          NULL,
    decision_note    VARCHAR(1000) NULL,
    decided_at       TIMESTAMPTZ   NULL,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_lr_org       FOREIGN KEY (organization_id) REFERENCES organizations (id) ON DELETE CASCADE,
    CONSTRAINT fk_lr_employee  FOREIGN KEY (employee_id)     REFERENCES users (id)         ON DELETE CASCADE,
    CONSTRAINT fk_lr_requester FOREIGN KEY (requested_by_id) REFERENCES users (id)         ON DELETE CASCADE,
    CONSTRAINT fk_lr_approver  FOREIGN KEY (approved_by_id)  REFERENCES users (id)         ON DELETE SET NULL,
    CONSTRAINT ck_lr_status    CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED')),
    CONSTRAINT ck_lr_type      CHECK (leave_type IN ('ANNUAL', 'SICK', 'UNPAID', 'MATERNITY', 'PATERNITY', 'BEREAVEMENT', 'OTHER')),
    CONSTRAINT ck_lr_dates     CHECK (to_date >= from_date),
    CONSTRAINT ck_lr_days      CHECK (days_requested > 0)
);
CREATE INDEX ix_lr_org        ON leave_requests (organization_id);
CREATE INDEX ix_lr_employee   ON leave_requests (employee_id);
CREATE INDEX ix_lr_status     ON leave_requests (status);
CREATE INDEX ix_lr_org_status ON leave_requests (organization_id, status);
CREATE INDEX ix_lr_date_range ON leave_requests (employee_id, from_date, to_date);

CREATE TABLE leave_balances (
    id              UUID        PRIMARY KEY,
    organization_id UUID        NOT NULL,
    user_id         UUID        NOT NULL,
    leave_type      VARCHAR(20) NOT NULL,
    year            INTEGER     NOT NULL,
    total_days      INTEGER     NOT NULL DEFAULT 0,
    used_days       INTEGER     NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_lb_org   FOREIGN KEY (organization_id) REFERENCES organizations (id) ON DELETE CASCADE,
    CONSTRAINT fk_lb_user  FOREIGN KEY (user_id)         REFERENCES users (id)         ON DELETE CASCADE,
    CONSTRAINT uq_lb_slot  UNIQUE (user_id, leave_type, year),
    CONSTRAINT ck_lb_type  CHECK (leave_type IN ('ANNUAL', 'SICK', 'UNPAID', 'MATERNITY', 'PATERNITY', 'BEREAVEMENT', 'OTHER')),
    CONSTRAINT ck_lb_days  CHECK (total_days >= 0 AND used_days >= 0)
);
CREATE INDEX ix_lb_org  ON leave_balances (organization_id);
CREATE INDEX ix_lb_user ON leave_balances (user_id);

CREATE TABLE notifications (
    id                UUID          PRIMARY KEY,
    organization_id   UUID          NULL,
    user_id           UUID          NOT NULL,
    title             VARCHAR(200)  NOT NULL,
    message           VARCHAR(1000) NOT NULL,
    type              VARCHAR(40)   NOT NULL,
    related_entity_id UUID          NULL,
    is_read           BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_notif_org  FOREIGN KEY (organization_id) REFERENCES organizations (id) ON DELETE CASCADE,
    CONSTRAINT fk_notif_user FOREIGN KEY (user_id)         REFERENCES users (id)         ON DELETE CASCADE,
    CONSTRAINT ck_notif_type CHECK (type IN (
        'LEAVE_REQUEST_SUBMITTED', 'LEAVE_APPROVED', 'LEAVE_REJECTED',
        'LATE_CHECK_IN', 'MANUAL_OVERRIDE', 'CHECK_OUT_REMINDER',
        'USER_DEACTIVATED', 'GENERAL'))
);
CREATE INDEX ix_notif_user_read ON notifications (user_id, is_read);
CREATE INDEX ix_notif_created   ON notifications (created_at);
CREATE INDEX ix_notif_org       ON notifications (organization_id);

CREATE TABLE refresh_tokens (
    id         UUID         PRIMARY KEY,
    user_id    UUID         NOT NULL,
    token_hash VARCHAR(128) NOT NULL,
    expires_at TIMESTAMPTZ  NOT NULL,
    revoked    BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_rt_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT uq_rt_hash UNIQUE (token_hash)
);
CREATE INDEX ix_rt_user    ON refresh_tokens (user_id);
CREATE INDEX ix_rt_expires ON refresh_tokens (expires_at);

CREATE TABLE device_tokens (
    id         UUID         PRIMARY KEY,
    user_id    UUID         NOT NULL,
    fcm_token  VARCHAR(512) NOT NULL,
    platform   VARCHAR(20)  NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_dt_user     FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT uq_dt_token    UNIQUE (fcm_token),
    CONSTRAINT ck_dt_platform CHECK (platform IN ('ANDROID', 'IOS', 'WEB'))
);
CREATE INDEX ix_dt_user ON device_tokens (user_id);
