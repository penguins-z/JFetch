package com.fd.jfetch.job;

import com.fd.jfetch.experience.ExperienceRequirement;
import com.fd.jfetch.experience.ExperienceSource;
import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "job", indexes = @Index(name = "idx_job_status", columnList = "status"))
public class Job {

    /** JSearch's job_id. */
    @Id
    @Column(length = 512)
    private String jobId;

    @Column(length = 1000)
    private String title;
    @Column(length = 500)
    private String employerName;
    @Column(length = 500)
    private String location;
    @Column(length = 4000)
    private String applyUrl;
    @Column(length = 255)
    private String publisher;
    private Instant postedAt;

    @Lob
    private String description;

    private Double expMinYears;
    private Double expMaxYears;
    private Boolean expFreshers;
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private ExperienceSource expSource = ExperienceSource.UNKNOWN;
    @Column(length = 500)
    private String expEvidence;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private JobStatus status = JobStatus.PENDING;

    @Column(nullable = false)
    private Instant firstFetchedAt;
    /** Last time JSearch returned this job — what the UI shows as "fetched on". */
    @Column(nullable = false)
    private Instant lastFetchedAt;

    protected Job() {
    }

    public Job(String jobId) {
        this.jobId = jobId;
    }

    public ExperienceRequirement experience() {
        return new ExperienceRequirement(expMinYears, expMaxYears, Boolean.TRUE.equals(expFreshers),
                expSource == null ? ExperienceSource.UNKNOWN : expSource, expEvidence);
    }

    public void setExperience(ExperienceRequirement exp) {
        this.expMinYears = exp.minYears();
        this.expMaxYears = exp.maxYears();
        this.expFreshers = exp.freshersAccepted();
        this.expSource = exp.source();
        this.expEvidence = truncate(exp.evidence(), 500);
    }

    static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    public String getJobId() { return jobId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = truncate(title, 1000); }
    public String getEmployerName() { return employerName; }
    public void setEmployerName(String employerName) { this.employerName = truncate(employerName, 500); }
    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = truncate(location, 500); }
    public String getApplyUrl() { return applyUrl; }
    public void setApplyUrl(String applyUrl) { this.applyUrl = applyUrl != null && applyUrl.length() > 4000 ? null : applyUrl; }
    public String getPublisher() { return publisher; }
    public void setPublisher(String publisher) { this.publisher = truncate(publisher, 255); }
    public Instant getPostedAt() { return postedAt; }
    public void setPostedAt(Instant postedAt) { this.postedAt = postedAt; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public JobStatus getStatus() { return status; }
    public void setStatus(JobStatus status) { this.status = status; }
    public Instant getFirstFetchedAt() { return firstFetchedAt; }
    public void setFirstFetchedAt(Instant firstFetchedAt) { this.firstFetchedAt = firstFetchedAt; }
    public Instant getLastFetchedAt() { return lastFetchedAt; }
    public void setLastFetchedAt(Instant lastFetchedAt) { this.lastFetchedAt = lastFetchedAt; }
}
