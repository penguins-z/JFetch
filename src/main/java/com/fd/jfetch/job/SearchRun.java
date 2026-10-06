package com.fd.jfetch.job;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * One JSearch API call, identified by its normalized parameters (the "query signature").
 * If a signature already exists, the cached jobs are reused and JSearch is not called again.
 */
@Entity
@Table(name = "search_run")
public class SearchRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 600)
    private String signature;

    @Column(nullable = false, length = 300)
    private String query;
    @Column(length = 10)
    private String country;
    @Column(length = 20)
    private String datePosted;
    private int numPages;

    // Criteria applied at fetch time (non-matching jobs are stored as CRITERIA_FAILED).
    @Column(length = 100)
    private String keyword;
    private Double minExp;
    private Double maxExp;
    private Boolean includeUnknown;

    @Column(nullable = false)
    private Instant fetchedAt;

    private int resultCount;

    /** Set by "Skip all": the next identical search re-fetches from JSearch instead of using the cache. */
    private Instant expiredAt;

    @ManyToMany
    @JoinTable(name = "search_run_job",
            joinColumns = @JoinColumn(name = "search_run_id"),
            inverseJoinColumns = @JoinColumn(name = "job_id"))
    private Set<Job> jobs = new LinkedHashSet<>();

    protected SearchRun() {
    }

    public SearchRun(SearchParams params, JobFilter criteria, Instant fetchedAt) {
        this.signature = signature(params, criteria);
        this.keyword = criteria.keyword();
        this.minExp = criteria.minYears();
        this.maxExp = criteria.maxYears();
        this.includeUnknown = criteria.includeUnknown();
        this.query = params.query();
        this.country = params.country();
        this.datePosted = params.datePosted();
        this.numPages = params.numPages();
        this.fetchedAt = fetchedAt;
    }

    public static String signature(SearchParams params, JobFilter criteria) {
        return params.signature() + "#" + criteria.signature();
    }

    public JobFilter criteria() {
        return new JobFilter(keyword, minExp, maxExp, Boolean.TRUE.equals(includeUnknown));
    }

    public Long getId() { return id; }
    public String getKeyword() { return keyword; }
    public Double getMinExp() { return minExp; }
    public Double getMaxExp() { return maxExp; }
    public boolean isIncludeUnknown() { return Boolean.TRUE.equals(includeUnknown); }
    public String getSignature() { return signature; }
    public String getQuery() { return query; }
    public String getCountry() { return country; }
    public String getDatePosted() { return datePosted; }
    public int getNumPages() { return numPages; }
    public Instant getFetchedAt() { return fetchedAt; }
    public Set<Job> getJobs() { return jobs; }
    public int getResultCount() { return resultCount; }
    public void setResultCount(int resultCount) { this.resultCount = resultCount; }
    public Instant getExpiredAt() { return expiredAt; }
    public boolean isExpired() { return expiredAt != null; }

    /** Replaces the cached result set after a re-fetch. */
    public void refreshed(Instant fetchedAt) {
        this.fetchedAt = fetchedAt;
        this.expiredAt = null;
        this.jobs.clear();
    }
}
