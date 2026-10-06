package com.fd.jfetch.job;

public enum JobStatus {
    PENDING, APPLIED, SKIPPED,
    /** Fetched, but didn't match the search's keyword/experience criteria at fetch time. */
    CRITERIA_FAILED;

    public String label() {
        String s = name().replace('_', ' ').toLowerCase();
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
