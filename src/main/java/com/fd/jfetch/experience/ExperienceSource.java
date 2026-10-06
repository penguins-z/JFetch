package com.fd.jfetch.experience;

/** Where a job's experience requirement came from. */
public enum ExperienceSource {
    /** JSearch's job_required_experience fields. */
    STRUCTURED,
    /** Parsed out of the title / qualifications / description text. */
    TEXT,
    UNKNOWN
}
