package com.attendance.api.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Helpers that keep optional query parameters non-null.
 *
 * <p>PostgreSQL cannot always infer the type of a bare {@code ? IS NULL} placeholder, and
 * it resolves {@code lower(?)} on an untyped null as {@code lower(bytea)}; Hibernate in turn
 * cannot expand a null collection into an {@code IN} list. Rather than scatter casts through
 * the JPQL, every optional filter is normalised here into a value that always binds: a
 * LIKE pattern, a wide date bound, a non-empty id list, or a companion boolean flag.
 */
public final class QueryParams {

    /** Matches every row, since all searched columns are NOT NULL. */
    public static final String MATCH_ALL = "%";

    /** Lower bound standing in for "no fromDate given". */
    public static final LocalDate DATE_MIN = LocalDate.of(1900, 1, 1);

    /** Upper bound standing in for "no toDate given". */
    public static final LocalDate DATE_MAX = LocalDate.of(9999, 12, 31);

    private static final UUID NIL_UUID = new UUID(0L, 0L);

    private QueryParams() {
    }

    /** Blank or null search text becomes a match-all LIKE pattern. */
    public static String likePattern(String search) {
        if (search == null || search.isBlank()) {
            return MATCH_ALL;
        }
        return "%" + search.trim().toLowerCase() + "%";
    }

    public static LocalDate fromOrMin(LocalDate fromDate) {
        return fromDate == null ? DATE_MIN : fromDate;
    }

    public static LocalDate toOrMax(LocalDate toDate) {
        return toDate == null ? DATE_MAX : toDate;
    }

    /** True when the caller placed no restriction on which users are visible. */
    public static boolean isUnrestricted(List<UUID> userIds) {
        return userIds == null;
    }

    /**
     * A never-empty list for the {@code IN} clause. The value is ignored whenever the
     * companion {@code allUsers} flag is true, but must still bind to something.
     */
    public static List<UUID> orPlaceholder(List<UUID> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return List.of(NIL_UUID);
        }
        return userIds;
    }

    /** Stand-in for an absent UUID filter, paired with an {@code any...} flag. */
    public static UUID orNil(UUID id) {
        return id == null ? NIL_UUID : id;
    }
}
