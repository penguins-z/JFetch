package com.fd.jfetch.jsearch;

import java.time.Instant;

/** One job from a JSearch response. Every field except jobId may be null. */
public record JobPosting(
        String jobId,
        String title,
        String employerName,
        String location,
        String applyUrl,
        String publisher,
        Instant postedAt,
        String description,
        String qualifications,
        Integer requiredExperienceMonths,
        Boolean noExperienceRequired) {
}
