package com.fd.jfetch.experience;

/**
 * A job's mandatory experience requirement in years. {@code maxYears == null} means open-ended
 * ("3+ years"). {@code freshersAccepted} means the listing explicitly welcomes freshers, which lets it
 * match a range starting at 0 whatever the stated years. {@code evidence} is the text/field the
 * values came from, for display.
 */
public record ExperienceRequirement(Double minYears, Double maxYears, boolean freshersAccepted,
                                    ExperienceSource source, String evidence) {

    public static final ExperienceRequirement UNKNOWN =
            new ExperienceRequirement(null, null, false, ExperienceSource.UNKNOWN, null);

    public ExperienceRequirement(Double minYears, Double maxYears, ExperienceSource source, String evidence) {
        this(minYears, maxYears, false, source, evidence);
    }

    public boolean isKnown() {
        return source != ExperienceSource.UNKNOWN && minYears != null;
    }

    public ExperienceRequirement withFreshers(String fresherEvidence) {
        if (!isKnown()) {
            return new ExperienceRequirement(0.0, 1.0, true, ExperienceSource.TEXT, fresherEvidence);
        }
        return new ExperienceRequirement(minYears, maxYears, true, source, evidence + " | " + fresherEvidence);
    }

    /**
     * True if the job's requirement overlaps the user's range [lo, hi], i.e. someone whose
     * experience is somewhere in that range could plausibly qualify. Fresher-friendly jobs are
     * treated as starting at 0 years.
     */
    public boolean overlaps(double lo, double hi) {
        if (!isKnown()) {
            return false;
        }
        double min = freshersAccepted ? 0 : minYears;
        return min <= hi && (maxYears == null || maxYears >= lo);
    }
}
