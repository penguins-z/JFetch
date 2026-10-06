package com.fd.jfetch.web;

import com.fd.jfetch.experience.ExperienceRequirement;
import com.fd.jfetch.job.Job;
import com.fd.jfetch.job.JobStatus;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Display-ready row for the listing templates. */
public record JobView(
        String jobId,
        String title,
        String employerName,
        String location,
        String publisher,
        String applyUrl,
        String ambitionBoxUrl,
        boolean ambitionBoxIsSearch,
        String ambitionBoxSearchUrl,
        String experienceLabel,
        String experienceEvidence,
        JobStatus status,
        String postedOn,
        String fetchedOn) {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM yyyy");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm");

    public static JobView of(Job job) {
        AmbitionBoxLinks.Link ab = AmbitionBoxLinks.forCompany(job.getEmployerName());
        ExperienceRequirement exp = job.experience();
        return new JobView(
                job.getJobId(),
                job.getTitle() != null ? job.getTitle() : "(untitled)",
                job.getEmployerName(),
                job.getLocation(),
                job.getPublisher(),
                job.getApplyUrl(),
                ab == null ? null : ab.url(),
                ab != null && ab.isSearch(),
                ab == null || ab.isSearch() ? null : AmbitionBoxLinks.searchUrl(job.getEmployerName()),
                label(exp),
                exp.isKnown() ? exp.source().name().toLowerCase() + ": " + exp.evidence() : null,
                job.getStatus(),
                format(job.getPostedAt(), DATE),
                format(job.getLastFetchedAt(), DATE_TIME));
    }

    private static String label(ExperienceRequirement exp) {
        if (!exp.isKnown()) return "Unknown";
        if (exp.freshersAccepted()) {
            boolean onlyFresher = exp.minYears() == 0 && exp.maxYears() != null && exp.maxYears() == 1
                    && !exp.evidence().contains(" | ");
            return onlyFresher ? "Freshers OK" : years(exp) + " · freshers OK";
        }
        return years(exp);
    }

    private static String years(ExperienceRequirement exp) {
        String min = fmt(exp.minYears());
        if (exp.maxYears() == null) return min + "+ yrs";
        if (exp.minYears().equals(exp.maxYears())) return min + " yrs";
        return min + "–" + fmt(exp.maxYears()) + " yrs";
    }

    private static String fmt(double d) {
        return d == Math.floor(d) ? Long.toString((long) d) : Double.toString(d);
    }

    private static String format(Instant i, DateTimeFormatter f) {
        return i == null ? null : f.format(i.atZone(ZoneId.systemDefault()));
    }
}
