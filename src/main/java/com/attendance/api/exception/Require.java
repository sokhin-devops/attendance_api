package com.attendance.api.exception;

import org.springframework.lang.NonNull;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Unwraps a repository lookup into a guaranteed-present value.
 *
 * <p>{@code Optional.orElseThrow()} is the idiomatic way to do this, but the JDK carries no
 * null annotations, so its result is of unknown nullity: assigning it to a {@code @NonNull}
 * return leaves static analysis unable to verify the contract. These helpers do the same job
 * with an explicit null check, which flow analysis <em>can</em> verify — so the non-null
 * guarantee holds all the way to the caller.
 */
public final class Require {

    private Require() {
    }

    /**
     * @param resource human-readable name used in the 404 message, e.g. {@code "User"}
     * @param id       identifier that was looked up
     * @throws ResourceNotFoundException when the lookup found nothing
     */
    @NonNull
    public static <T> T found(Optional<T> lookup, String resource, Object id) {
        T value = lookup.orElse(null);
        if (value == null) {
            throw new ResourceNotFoundException(resource, id);
        }
        return value;
    }

    /** Same guarantee, for lookups whose absence means something other than a 404. */
    @NonNull
    public static <T> T present(Optional<T> lookup, Supplier<? extends RuntimeException> onMissing) {
        T value = lookup.orElse(null);
        if (value == null) {
            throw onMissing.get();
        }
        return value;
    }
}
