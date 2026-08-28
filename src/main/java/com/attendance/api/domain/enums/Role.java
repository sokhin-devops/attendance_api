package com.attendance.api.domain.enums;

/** Platform and tenant roles. SUPER_ADMIN is platform-scoped (no organization). */
public enum Role {
    SUPER_ADMIN,
    ORG_ADMIN,
    MANAGER,
    EMPLOYEE;

    /** Spring Security authority name, e.g. {@code ROLE_ORG_ADMIN}. */
    public String authority() {
        return "ROLE_" + name();
    }
}
