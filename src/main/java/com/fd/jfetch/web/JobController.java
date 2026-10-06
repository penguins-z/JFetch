package com.fd.jfetch.web;

import com.fd.jfetch.job.*;
import com.fd.jfetch.jsearch.JSearchException;
import com.fd.jfetch.jsearch.JSearchProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.ArrayList;
import java.util.List;

@Controller
public class JobController {

    private final JobService service;
    private final JSearchProperties jsearch;
    private final int pageSize;

    public JobController(JobService service, JSearchProperties jsearch,
                         @Value("${jfetch.page-size:10}") int pageSize) {
        this.service = service;
        this.jsearch = jsearch;
        this.pageSize = Math.max(1, pageSize);
    }

    public record PageLink(int number, String url, boolean current) {
    }

    /** Values for the search form; flashed back after a failed search so nothing is lost. */
    public record SearchForm(String query, String country, String datePosted, int numPages, Double minExp,
                             Double maxExp, String keyword, boolean includeUnknown) implements java.io.Serializable {
        static final SearchForm DEFAULTS = new SearchForm("java developer", "in", "all", 3, 0.0, 2.0, "java", false);
    }

    /** Flash-safe copy of a fetch outcome for the results banner. */
    public record FetchSummary(boolean fromCache, int newJobs, int passed, int failed, int duplicates,
                               int pagesUsed, int apiCalls) implements java.io.Serializable {
        static FetchSummary of(JobService.RunResult r) {
            return new FetchSummary(r.fromCache(), r.newJobs(), r.passed(), r.failed(), r.duplicates(),
                    r.pagesUsed(), r.apiCalls());
        }
    }

    @GetMapping("/")
    public String index(Model model) {
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", SearchForm.DEFAULTS);
        }
        model.addAttribute("runs", service.recentRuns());
        model.addAttribute("hasApiKey", jsearch.hasApiKey());
        model.addAttribute("maxPages", jsearch.maxPages());
        model.addAttribute("maxScanPages", jsearch.maxScanPages());
        model.addAttribute("active", "search");
        return "index";
    }

    @PostMapping("/search")
    public String search(@RequestParam(defaultValue = "java developer") String query,
                         @RequestParam(required = false) String country,
                         @RequestParam(defaultValue = "all") String datePosted,
                         @RequestParam(defaultValue = "3") int numPages,
                         @RequestParam(required = false) Double minExp,
                         @RequestParam(required = false) Double maxExp,
                         @RequestParam(defaultValue = "java") String keyword,
                         @RequestParam(defaultValue = "false") boolean includeUnknown,
                         RedirectAttributes redirect) {
        redirect.addFlashAttribute("form", new SearchForm(query, country, datePosted, numPages, minExp, maxExp,
                keyword, includeUnknown));
        if (query.isBlank()) {
            redirect.addFlashAttribute("error", "Search query can't be empty.");
            return "redirect:/";
        }
        SearchParams params = new SearchParams(query, country, datePosted,
                Math.max(1, Math.min(numPages, jsearch.maxPages())));
        JobFilter criteria = new JobFilter(keyword, minExp, maxExp, includeUnknown);
        JobService.RunResult result;
        try {
            result = service.getOrFetch(params, criteria);
        } catch (JSearchException e) {
            redirect.addFlashAttribute("error", e.getMessage());
            return "redirect:/";
        }
        redirect.addFlashAttribute("summary", FetchSummary.of(result));
        if (result.warning() != null) {
            redirect.addFlashAttribute("error", result.warning());
        }
        redirect.addAttribute("run", result.run().getId());
        return "redirect:/results";
    }

    /** Jobs the run brought in that passed its criteria. Criteria are fixed at fetch time. */
    @GetMapping("/results")
    public String results(@RequestParam Long run,
                          @RequestParam(defaultValue = "1") int page,
                          HttpServletRequest request, Model model) {
        SearchRun searchRun = service.findRun(run)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown search"));
        model.addAttribute("run", searchRun);
        populate(model, service.passedJobsForRun(run), searchRun.criteria(), page, request);
        model.addAttribute("active", "search");
        return "results";
    }

    // Status pages: optional view filters on top of the stored status (no keyword filter by default).

    @GetMapping("/pending")
    public String pending(@RequestParam(required = false) Double minExp, @RequestParam(required = false) Double maxExp,
                          @RequestParam(defaultValue = "") String keyword,
                          @RequestParam(defaultValue = "false") boolean includeUnknown,
                          @RequestParam(defaultValue = "1") int page, HttpServletRequest request, Model model) {
        return byStatus(JobStatus.PENDING, "pending", minExp, maxExp, keyword, includeUnknown, page, request, model);
    }

    @GetMapping("/applied")
    public String applied(@RequestParam(required = false) Double minExp, @RequestParam(required = false) Double maxExp,
                          @RequestParam(defaultValue = "") String keyword,
                          @RequestParam(defaultValue = "false") boolean includeUnknown,
                          @RequestParam(defaultValue = "1") int page, HttpServletRequest request, Model model) {
        return byStatus(JobStatus.APPLIED, "applied", minExp, maxExp, keyword, includeUnknown, page, request, model);
    }

    @GetMapping("/skipped")
    public String skipped(@RequestParam(required = false) Double minExp, @RequestParam(required = false) Double maxExp,
                          @RequestParam(defaultValue = "") String keyword,
                          @RequestParam(defaultValue = "false") boolean includeUnknown,
                          @RequestParam(defaultValue = "1") int page, HttpServletRequest request, Model model) {
        return byStatus(JobStatus.SKIPPED, "skipped", minExp, maxExp, keyword, includeUnknown, page, request, model);
    }

    @GetMapping("/failed")
    public String failed(@RequestParam(required = false) Double minExp, @RequestParam(required = false) Double maxExp,
                         @RequestParam(defaultValue = "") String keyword,
                         @RequestParam(defaultValue = "false") boolean includeUnknown,
                         @RequestParam(defaultValue = "1") int page, HttpServletRequest request, Model model) {
        return byStatus(JobStatus.CRITERIA_FAILED, "failed", minExp, maxExp, keyword, includeUnknown, page, request, model);
    }

    private String byStatus(JobStatus status, String view, Double minExp, Double maxExp, String keyword,
                            boolean includeUnknown, int page, HttpServletRequest request, Model model) {
        JobFilter filter = new JobFilter(keyword, minExp, maxExp, includeUnknown);
        populate(model, service.jobsWithStatus(status, filter), filter, page, request);
        model.addAttribute("active", view);
        return view;
    }

    @PostMapping("/jobs/status")
    public String updateStatus(@RequestParam String jobId,
                               @RequestParam JobStatus status,
                               @RequestParam(defaultValue = "/") String returnTo,
                               RedirectAttributes redirect) {
        if (!service.updateStatus(jobId, status)) {
            redirect.addFlashAttribute("error", "That job no longer exists.");
        }
        return "redirect:" + safeLocalPath(returnTo);
    }

    @PostMapping("/jobs/skip-all")
    public String skipAll(@RequestParam(defaultValue = "/pending") String returnTo, RedirectAttributes redirect) {
        JobService.SkipAllResult r = service.skipAllPending();
        redirect.addFlashAttribute("message", "Skipped " + r.skippedJobs() + " pending job(s) and expired "
                + r.expiredSearches() + " cached search(es). Re-running a search will fetch fresh results from JSearch.");
        return "redirect:" + safeLocalPath(returnTo);
    }

    /** Prevents open redirects: only same-app relative paths are allowed. */
    static String safeLocalPath(String path) {
        if (path == null || !path.startsWith("/") || path.startsWith("//") || path.contains("\\")
                || path.chars().anyMatch(Character::isISOControl)) {
            return "/";
        }
        return path;
    }

    /** Server-side pagination over the already-filtered list; never touches the JSearch API. */
    private void populate(Model model, List<Job> all, JobFilter filter, int requestedPage, HttpServletRequest request) {
        int total = all.size();
        int totalPages = Math.max(1, (total + pageSize - 1) / pageSize);
        int page = Math.min(Math.max(1, requestedPage), totalPages);
        int from = (page - 1) * pageSize;
        List<JobView> rows = all.subList(from, Math.min(from + pageSize, total)).stream().map(JobView::of).toList();

        UriComponentsBuilder base = ServletUriComponentsBuilder.fromRequest(request).replaceQueryParam("page");
        List<PageLink> links = new ArrayList<>();
        for (int i = 1; i <= totalPages; i++) {
            links.add(new PageLink(i, pageUrl(base, i), i == page));
        }

        model.addAttribute("jobs", rows);
        model.addAttribute("total", total);
        model.addAttribute("page", page);
        model.addAttribute("totalPages", totalPages);
        model.addAttribute("pageLinks", links);
        model.addAttribute("prevUrl", page > 1 ? pageUrl(base, page - 1) : null);
        model.addAttribute("nextUrl", page < totalPages ? pageUrl(base, page + 1) : null);
        model.addAttribute("returnTo", pageUrl(base, page));
        model.addAttribute("filter", filter);
        model.addAttribute("statuses", JobStatus.values());
        model.addAttribute("pendingCount", service.countPending());
    }

    private static String pageUrl(UriComponentsBuilder base, int page) {
        var c = base.cloneBuilder().replaceQueryParam("page", page).build();
        String q = c.getQuery();
        return c.getPath() + (q == null ? "" : "?" + q);
    }
}
