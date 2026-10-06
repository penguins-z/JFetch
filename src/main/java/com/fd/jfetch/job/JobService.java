package com.fd.jfetch.job;

import com.fd.jfetch.experience.ExperienceParser;
import com.fd.jfetch.jsearch.JSearchClient;
import com.fd.jfetch.jsearch.JSearchException;
import com.fd.jfetch.jsearch.JSearchProperties;
import com.fd.jfetch.jsearch.JobPosting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
public class JobService {

    private static final Logger log = LoggerFactory.getLogger(JobService.class);

    /** Newest postings first; unknown dates last. */
    private static final Comparator<Job> ORDER = Comparator
            .comparing(Job::getPostedAt, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(Job::getJobId);

    private final JSearchClient client;
    private final JobRepository jobs;
    private final SearchRunRepository runs;
    private final JSearchProperties props;

    public JobService(JSearchClient client, JobRepository jobs, SearchRunRepository runs, JSearchProperties props) {
        this.client = client;
        this.jobs = jobs;
        this.runs = runs;
        this.props = props;
    }

    /**
     * Outcome of a search. When served from cache only {@code run} and {@code fromCache} matter.
     *
     * @param newJobs     never-seen job_ids stored by this fetch ({@code passed + failed})
     * @param duplicates  job_ids already in the DB (any status), ignored
     * @param pagesUsed   JSearch pages requested in total
     * @param apiCalls    HTTP calls made to JSearch
     * @param warning     set if a later call failed and the results are partial
     */
    public record RunResult(SearchRun run, boolean fromCache, int newJobs, int passed, int failed,
                            int duplicates, int pagesUsed, int apiCalls, String warning) {
        static RunResult cached(SearchRun run) {
            return new RunResult(run, true, 0, 0, 0, 0, 0, 0, null);
        }
    }

    public record SkipAllResult(int skippedJobs, int expiredSearches) {
    }

    /**
     * Returns the cached run for these params + criteria, or fetches from JSearch.
     * <p>
     * Fetching: any job_id already in the DB is ignored, whatever its status. Pages are requested one
     * at a time (one billed request each), following JSearch's cursor from the first page, until
     * {@code numPages * 10} new jobs are found, JSearch runs out of results, or
     * {@code jsearch.max-scan-pages} is reached. Each new job is stored
     * as PENDING if it matches the criteria, otherwise CRITERIA_FAILED. An expired run (see
     * {@link #skipAllPending()}) is re-fetched and its row reused.
     */
    @Transactional
    public RunResult getOrFetch(SearchParams params, JobFilter criteria) {
        Optional<SearchRun> cached = runs.findBySignature(SearchRun.signature(params, criteria));
        if (cached.isPresent() && !cached.get().isExpired()) {
            return RunResult.cached(cached.get());
        }

        int target = Math.max(1, params.numPages()) * 10;
        int maxScan = Math.max(1, props.maxScanPages());

        Map<String, JobPosting> fresh = new LinkedHashMap<>();
        Set<String> seenCursors = new HashSet<>();
        int duplicates = 0, pages = 0;
        String cursor = null, warning = null;
        while (fresh.size() < target && pages < maxScan) {
            JSearchClient.Page page;
            try {
                page = client.searchPage(params, cursor);
            } catch (JSearchException e) {
                if (pages == 0) throw e;
                warning = "Stopped early after page " + pages + ": " + e.getMessage();
                log.warn(warning);
                break;
            }
            pages++;
            for (JobPosting p : page.jobs()) {
                if (fresh.containsKey(p.jobId())) continue;
                if (jobs.existsById(p.jobId())) {
                    duplicates++;
                } else {
                    fresh.put(p.jobId(), p);
                }
            }
            cursor = page.nextCursor();
            // End of results: empty page, no next cursor, or the API handing back a cursor it already gave.
            if (page.jobs().isEmpty() || cursor == null || !seenCursors.add(cursor)) break;
        }

        Instant now = Instant.now();
        SearchRun run = cached.orElseGet(() -> new SearchRun(params, criteria, now));
        run.refreshed(now);
        Pattern kw = criteria.keywordPattern();
        int passed = 0;
        for (JobPosting p : fresh.values()) {
            Job job = new Job(p.jobId());
            job.setFirstFetchedAt(now);
            apply(job, p, now);
            boolean ok = criteria.matches(job, kw);
            job.setStatus(ok ? JobStatus.PENDING : JobStatus.CRITERIA_FAILED);
            if (ok) passed++;
            run.getJobs().add(jobs.save(job));
        }
        run.setResultCount(fresh.size());
        return new RunResult(runs.save(run), false, fresh.size(), passed, fresh.size() - passed,
                duplicates, pages, pages, warning);
    }

    private static void apply(Job job, JobPosting p, Instant now) {
        job.setTitle(p.title());
        job.setEmployerName(p.employerName());
        job.setLocation(p.location());
        job.setApplyUrl(p.applyUrl());
        job.setPublisher(p.publisher());
        job.setPostedAt(p.postedAt());
        job.setDescription(p.description());
        job.setExperience(ExperienceParser.parse(p.requiredExperienceMonths(), p.noExperienceRequired(),
                p.title(), p.qualifications(), p.description()));
        job.setLastFetchedAt(now);
    }

    /**
     * Marks every PENDING job as SKIPPED and expires every cached search, so re-running a search
     * calls JSearch again (and, since all known job_ids are ignored, only brings in new listings).
     */
    @Transactional
    public SkipAllResult skipAllPending() {
        int skipped = jobs.skipAllPending();
        int expired = runs.expireAll(Instant.now());
        return new SkipAllResult(skipped, expired);
    }

    @Transactional(readOnly = true)
    public long countPending() {
        return jobs.countByStatus(JobStatus.PENDING);
    }

    @Transactional(readOnly = true)
    public Optional<SearchRun> findRun(Long id) {
        return runs.findById(id);
    }

    @Transactional(readOnly = true)
    public List<SearchRun> recentRuns() {
        return runs.findAllByOrderByFetchedAtDesc();
    }

    /** Jobs this run brought in that passed its criteria (any status except CRITERIA_FAILED). */
    @Transactional(readOnly = true)
    public List<Job> passedJobsForRun(Long runId) {
        return jobs.findBySearchRun(runId).stream()
                .filter(j -> j.getStatus() != JobStatus.CRITERIA_FAILED)
                .sorted(ORDER).toList();
    }

    /** Everything is filtered and sorted in memory: the data set is small and already local. */
    @Transactional(readOnly = true)
    public List<Job> jobsWithStatus(JobStatus status, JobFilter filter) {
        Pattern kw = filter.keywordPattern();
        return jobs.findByStatus(status).stream().filter(j -> filter.matches(j, kw)).sorted(ORDER).toList();
    }

    @Transactional
    public boolean updateStatus(String jobId, JobStatus status) {
        return jobs.findById(jobId).map(j -> {
            j.setStatus(status);
            return true;
        }).orElse(false);
    }
}
