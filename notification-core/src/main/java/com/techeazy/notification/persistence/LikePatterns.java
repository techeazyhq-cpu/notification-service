package com.techeazy.notification.persistence;

import java.util.Locale;

/** Builds safe case-insensitive "contains" patterns for {@code CriteriaBuilder.like(lower(x), pattern, ESCAPE)}. */
public final class LikePatterns {

    public static final char ESCAPE = '\\';

    private LikePatterns() {}

    /** Lower-cases the term and escapes %, _ and the escape char so user input matches literally. */
    public static String contains(String term) {
        String escaped = term.trim().toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped + "%";
    }
}
