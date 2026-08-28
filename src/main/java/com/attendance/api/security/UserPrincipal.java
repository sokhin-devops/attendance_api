package com.attendance.api.security;

import com.attendance.api.domain.User;
import com.attendance.api.domain.enums.Role;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

/** Authenticated identity carried on the security context, tenant id included. */
@Getter
public class UserPrincipal implements UserDetails {

    private static final long serialVersionUID = 1L;

    @NonNull
    private final UUID id;

    /** Null for SUPER_ADMIN, which is platform-scoped rather than tenant-scoped. */
    @Nullable
    private final UUID organizationId;

    @NonNull
    private final String email;

    /** Null on a principal rebuilt from a token, where no credential is carried. */
    @Nullable
    private final String password;

    @NonNull
    private final String fullName;

    @NonNull
    private final Role role;
    private final boolean active;

    public UserPrincipal(@NonNull UUID id, @Nullable UUID organizationId,
                         @NonNull String email, @Nullable String password,
                         @NonNull String fullName, @NonNull Role role, boolean active) {
        this.id = id;
        this.organizationId = organizationId;
        this.email = email;
        this.password = password;
        this.fullName = fullName;
        this.role = role;
        this.active = active;
    }

    /**
     * @throws IllegalStateException if the user is missing an identity field, which would
     *                               mean an unsaved or partially built entity reached the
     *                               security layer
     */
    public static UserPrincipal from(User user) {
        UUID id = user.getId();
        String email = user.getEmail();
        String fullName = user.getFullName();
        Role role = user.getRole();
        if (id == null || email == null || fullName == null || role == null) {
            throw new IllegalStateException(
                    "Cannot build a security principal from an incomplete user: " + user.getId());
        }
        return new UserPrincipal(id, user.resolveOrganizationId(), email,
                user.getPasswordHash(), fullName, role, user.isActive());
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority(role.authority()));
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return active;
    }

    public boolean isSuperAdmin() {
        return role == Role.SUPER_ADMIN;
    }

    public boolean isOrgAdmin() {
        return role == Role.ORG_ADMIN;
    }

    public boolean isManager() {
        return role == Role.MANAGER;
    }

    public boolean isEmployee() {
        return role == Role.EMPLOYEE;
    }
}
