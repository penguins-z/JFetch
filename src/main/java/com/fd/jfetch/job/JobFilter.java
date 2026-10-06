package com.fd.jfetch.job;

import java.util.regex.Pattern;

/**
 * Local filter applied to cached jobs.
 *
 * @param minYears/maxYears  user's experience range; both null = no experience filter
 * @param includeUnknown     also keep jobs whose experience couldn't be determined
 */
public record JobFilter(String keyword, Double minYears, Double maxYears, boolean includeUnknown) {

    public JobFilter {
        keyword = keyword == null || keyword.isBlank() ? null : keyword.trim();
        if (minYears != null && maxYears != null && minYears > maxYears) {
            Double t = minYears;
            minYears = maxYears;
            maxYears = t;
        }
    }

    /** Part of the search signature: the same JSearch query with different criteria is a different search. */
    public String signature() {
        return String.join("|",
                keyword == null ? "" : keyword.toLowerCase(java.util.Locale.ROOT),
                minYears == null ? "" : minYears.toString(),
                maxYears == null ? "" : maxYears.toString(),
                Boolean.toString(includeUnknown));
    }

    /** e.g. "java, 0–2 yrs", "(any keyword), 3+ yrs +unknown". */
    public String label() {
        String exp = !hasExperienceRange() ? "any experience"
                : maxYears == null ? fmt(minYears) + "+ yrs"
                : fmt(minYears == null ? 0 : minYears) + "–" + fmt(maxYears) + " yrs";
        return (keyword == null ? "(any keyword)" : keyword) + ", " + exp + (includeUnknown ? " +unknown" : "");
    }

    private static String fmt(double d) {
        return d == Math.floor(d) ? Long.toString((long) d) : Double.toString(d);
    }

    public boolean hasExperienceRange() {
        return minYears != null || maxYears != null;
    }

    /**
     * Whole-word-ish, case-insensitive: "java" matches "Java", "Core Java", "Java8", "Java/J2EE"
     * but not "JavaScript".
     */
    public Pattern keywordPattern() {
        return keyword == null ? null : Pattern.compile(
                "(?<![\\p{L}\\p{N}])" + Pattern.quote(keyword) + "(?!\\p{L})",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    public boolean matches(Job job) {
        return matches(job, keywordPattern());
    }

    public boolean matches(Job job, Pattern kw) {
        if (kw != null && !(find(kw, job.getTitle()) || find(kw, job.getDescription()))) {
            return false;
        }
        if (!hasExperienceRange()) {
            return true;
        }
        var exp = job.experience();
        if (!exp.isKnown()) {
            return includeUnknown;
        }
        return exp.overlaps(minYears == null ? 0 : minYears, maxYears == null ? Double.MAX_VALUE : maxYears);
    }

    private static boolean find(Pattern p, String s) {
        return s != null && p.matcher(s).find();
    }
}
