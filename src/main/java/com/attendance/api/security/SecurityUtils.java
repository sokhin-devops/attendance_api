package com.attendance.api.security;

import com.attendance.api.exception.AccessDeniedBusinessException;
import com.attendance.api.exception.Require;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;
import java.util.UUID;

/**
 * Static access to the authenticated principal.
 *
 * <p>The {@code require*} accessors are declared {@code @NonNull} because they throw rather
 * than return null when there is no authenticated caller. Stating that explicitly lets
 * callers pass their results straight into Spring Data, whose parameters are non-null
 * by default.
 */
public final class SecurityUtils {

    private SecurityUtils() {
    }

    public static Optional<UserPrincipal> currentPrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()
                || !(auth.getPrincipal() instanceof UserPrincipal principal)) {
            return Optional.empty();
        }
        return Optional.of(principal);
    }

    @NonNull
    public static UserPrincipal requirePrincipal() {
        return Require.present(currentPrincipal(),
                () -> new AccessDeniedBusinessException("No authenticated user in context"));
    }

    @NonNull
    public static UUID currentUserId() {
        return requirePrincipal().getId();
    }

    /** @throws AccessDeniedBusinessException for a super admin, who has no tenant. */
    @NonNull
    public static UUID requireOrganizationId() {
        UserPrincipal principal = requirePrincipal();
        UUID organizationId = principal.getOrganizationId();
        if (organizationId == null) {
            throw new AccessDeniedBusinessException(
                    "This endpoint is tenant-scoped and cannot be called by a platform super admin");
        }
        return organizationId;
    }
}
