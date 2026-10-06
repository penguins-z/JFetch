package com.fd.jfetch.job;

import java.util.Locale;
import java.util.Set;

/**
 * Parameters that change what JSearch returns. Experience range and keyword are deliberately NOT
 * here: they're applied locally to the cached jobs, so changing them never costs an API call.
 */
public record SearchParams(String query, String country, String datePosted, int numPages) {

    public static final Set<String> DATE_POSTED_VALUES = Set.of("all", "today", "3days", "week", "month");

    public SearchParams {
        query = query == null ? "" : query.trim().replaceAll("\\s+", " ");
        country = country == null || country.isBlank() ? null : country.trim().toLowerCase(Locale.ROOT);
        datePosted = datePosted == null || !DATE_POSTED_VALUES.contains(datePosted) ? "all" : datePosted;
    }

    public String signature() {
        return String.join("|",
                query.toLowerCase(Locale.ROOT),
                country == null ? "" : country,
                datePosted,
                Integer.toString(numPages));
    }
}
