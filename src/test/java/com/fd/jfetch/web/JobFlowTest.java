package com.fd.jfetch.web;

import com.fd.jfetch.jsearch.JSearchClient;
import com.fd.jfetch.jsearch.JSearchException;
import com.fd.jfetch.jsearch.JobPosting;
import com.fd.jfetch.job.JobRepository;
import com.fd.jfetch.job.JobStatus;
import com.fd.jfetch.job.SearchParams;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:flow", "spring.jpa.hibernate.ddl-auto=create-drop",
        "jfetch.page-size=10", "jsearch.max-scan-pages=12"})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class JobFlowTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    JobRepository jobs;
    @MockitoBean
    JSearchClient client;

    private static JobPosting job(String id, String title, String desc) {
        return new JobPosting(id, title, "Infosys Ltd", "Pune", "https://example.com/" + id, "LinkedIn",
                Instant.parse("2026-10-01T00:00:00Z"), desc, null, null, null);
    }

    /**
     * Fake JSearch with {@code totalPages} pages of 10 jobs, cursor "p2", "p3"... Job idx passes the
     * 0-2 yr criteria when idx % 30 < 7, i.e. 7 of every 30.
     */
    private void fakeJSearch(int totalPages) {
        when(client.searchPage(any(), any())).thenAnswer(inv -> {
            String cursor = inv.getArgument(1);
            int p = cursor == null ? 1 : Integer.parseInt(cursor.substring(1));
            if (p > totalPages) return new JSearchClient.Page(List.of(), null);
            List<JobPosting> out = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                int idx = (p - 1) * 10 + i;
                boolean passes = idx % 30 < 7;
                out.add(job("job" + idx, "Java Developer " + idx,
                        passes ? "0-2 years of experience" : "5+ years of experience"));
            }
            return new JSearchClient.Page(out, p < totalPages ? "p" + (p + 1) : null);
        });
    }

    private void fakeJSearch(List<JobPosting> postings) {
        when(client.searchPage(any(), any())).thenReturn(new JSearchClient.Page(postings, null));
    }

    private static org.springframework.test.web.servlet.ResultMatcher summary(Consumer<JobController.FetchSummary> check) {
        return r -> check.accept((JobController.FetchSummary) r.getFlashMap().get("summary"));
    }

    private String search() throws Exception {
        return mvc.perform(post("/search").param("query", "java developer").param("country", "in")
                        .param("numPages", "3").param("minExp", "0").param("maxExp", "2").param("keyword", "java"))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl();
    }

    @Test
    void criteriaFailedSkipAllThenOnlyNewListings() throws Exception {
        fakeJSearch(100);

        // 1st search: 30 jobs, 7 pass -> PENDING, 23 -> CRITERIA_FAILED
        String location = search();
        verify(client).searchPage(any(), isNull());
        verify(client, times(3)).searchPage(any(), any()); // 1 page per call
        assertThat(jobs.findByStatus(JobStatus.PENDING)).hasSize(7);
        assertThat(jobs.findByStatus(JobStatus.CRITERIA_FAILED)).hasSize(23);
        mvc.perform(get(location)).andExpect(status().isOk())
                .andExpect(content().string(containsString("7</span> matching job(s)")))
                .andExpect(content().string(containsString("ambitionbox.com/overview/infosys-overview")));
        mvc.perform(get("/failed")).andExpect(content().string(containsString("23</span> matching job(s)")));

        // Same search again without expiring: cache, no API call
        search();
        verify(client, times(3)).searchPage(any(), any());

        // Apply to 5, skip-all the remaining 2
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/jobs/status").param("jobId", "job" + i).param("status", "APPLIED"));
        }
        mvc.perform(post("/jobs/skip-all")).andExpect(flash().attribute("message", containsString("Skipped 2")));
        assertThat(jobs.findByStatus(JobStatus.APPLIED)).hasSize(5);
        assertThat(jobs.findByStatus(JobStatus.SKIPPED)).hasSize(2);
        assertThat(jobs.findByStatus(JobStatus.PENDING)).isEmpty();
        mvc.perform(get(location)).andExpect(content().string(containsString("has expired")));

        // Re-query: pages 1-3 are all known -> ignored; pages 4-6 give 30 brand-new jobs (6 billed pages)
        String again = mvc.perform(post("/search").param("query", "java developer").param("country", "in")
                        .param("numPages", "3").param("minExp", "0").param("maxExp", "2").param("keyword", "java"))
                .andExpect(summary(s -> {
                    assertThat(s.newJobs()).isEqualTo(30);
                    assertThat(s.duplicates()).isEqualTo(30);
                    assertThat(s.passed()).isEqualTo(7);
                    assertThat(s.failed()).isEqualTo(23);
                    assertThat(s.apiCalls()).isEqualTo(6);
                }))
                .andReturn().getResponse().getRedirectedUrl();
        assertThat(again).isEqualTo(location);
        verify(client).searchPage(any(), eq("p6"));
        assertThat(jobs.count()).isEqualTo(60);
        assertThat(jobs.findByStatus(JobStatus.APPLIED)).hasSize(5);   // untouched
        assertThat(jobs.findByStatus(JobStatus.SKIPPED)).hasSize(2);   // untouched
        assertThat(jobs.findByStatus(JobStatus.PENDING)).hasSize(7);   // only the new passing ones
        assertThat(jobs.findById("job30").orElseThrow().getStatus()).isEqualTo(JobStatus.PENDING);
        mvc.perform(get(again)).andExpect(content().string(containsString("7</span> matching job(s)")))
                .andExpect(content().string(containsString("Java Developer 30")))
                .andExpect(content().string(not(containsString("Java Developer 0<"))));
    }

    @Test
    void scanStopsAtEndOfResults() throws Exception {
        fakeJSearch(4); // only 40 jobs exist in total
        search();
        mvc.perform(post("/jobs/skip-all"));
        // Pages 1-3 known; page 4 has 10 new jobs and no next cursor -> end of results, stop.
        mvc.perform(post("/search").param("query", "java developer").param("country", "in")
                        .param("numPages", "3").param("minExp", "0").param("maxExp", "2").param("keyword", "java"))
                .andExpect(summary(s -> {
                    assertThat(s.newJobs()).isEqualTo(10);
                    assertThat(s.apiCalls()).isEqualTo(4);
                }));
        verify(client, times(3 + 4)).searchPage(any(), any());
    }

    @Test
    void scanStopsAtMaxScanPages() throws Exception {
        fakeJSearch(100);
        // A 10-page search makes pages 1-10 known (dedup is global, across all searches).
        mvc.perform(post("/search").param("query", "java developer").param("numPages", "10"));
        mvc.perform(post("/jobs/skip-all"));
        clearInvocations(client);
        // 3-page search: pages 1-10 known, 11-12 give 20 new; the 12-page cap stops it short of 30.
        mvc.perform(post("/search").param("query", "java developer").param("country", "in")
                        .param("numPages", "3").param("minExp", "0").param("maxExp", "2").param("keyword", "java"))
                .andExpect(summary(s -> {
                    assertThat(s.pagesUsed()).isEqualTo(12);
                    assertThat(s.apiCalls()).isEqualTo(12);
                    assertThat(s.newJobs()).isEqualTo(20);
                    assertThat(s.duplicates()).isEqualTo(100);
                }));
        verify(client, times(12)).searchPage(any(), any());
    }

    @Test
    void unknownExperienceAndJavaScriptAreCriteriaFailed() throws Exception {
        fakeJSearch(List.of(
                job("ok", "Java Developer", "1 year experience"),
                job("js", "JavaScript Developer", "1 year experience"),
                job("unknown", "Java Engineer", "Great team"),
                new JobPosting("broken", null, null, null, null, null, null, null, null, null, null)));
        search();
        assertThat(jobs.findById("ok").orElseThrow().getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(jobs.findById("js").orElseThrow().getStatus()).isEqualTo(JobStatus.CRITERIA_FAILED);
        assertThat(jobs.findById("unknown").orElseThrow().getStatus()).isEqualTo(JobStatus.CRITERIA_FAILED);
        assertThat(jobs.findById("broken").orElseThrow().getStatus()).isEqualTo(JobStatus.CRITERIA_FAILED);
        mvc.perform(get("/failed")).andExpect(status().isOk())
                .andExpect(content().string(containsString("(untitled)")))
                .andExpect(content().string(containsString("unknown company")));

        // Rescue a job from the failed page; open redirects are neutralised.
        mvc.perform(post("/jobs/status").param("jobId", "unknown").param("status", "PENDING").param("returnTo", "//evil.com"))
                .andExpect(redirectedUrl("/"));
        assertThat(jobs.findById("unknown").orElseThrow().getStatus()).isEqualTo(JobStatus.PENDING);
    }

    @Test
    void emptyResultsAndApiErrors() throws Exception {
        fakeJSearch(List.of());
        String location = mvc.perform(post("/search").param("query", "nothing here"))
                .andReturn().getResponse().getRedirectedUrl();
        mvc.perform(get(location)).andExpect(status().isOk())
                .andExpect(content().string(containsString("No jobs match these filters.")));
        verify(client, times(1)).searchPage(any(), any());

        when(client.searchPage(any(), any())).thenThrow(new JSearchException("JSearch rate limit hit (HTTP 429)."));
        var failed = mvc.perform(post("/search").param("query", "java").param("keyword", "spring")
                        .param("datePosted", "week").param("numPages", "2").param("maxExp", "4"))
                .andExpect(redirectedUrl("/"))
                .andExpect(flash().attribute("error", containsString("429")))
                .andReturn().getFlashMap();
        // The form keeps what was typed instead of resetting to defaults.
        mvc.perform(get("/").flashAttrs(failed)).andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"query\" value=\"java\"")))
                .andExpect(content().string(containsString("name=\"keyword\" value=\"spring\"")))
                .andExpect(content().string(containsString("value=\"week\" selected")))
                .andExpect(content().string(containsString("name=\"maxExp\" value=\"4.0\"")));
        mvc.perform(get("/")).andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"query\" value=\"java developer\"")));
        for (String page : List.of("/pending", "/applied", "/skipped", "/failed")) {
            mvc.perform(get(page)).andExpect(status().isOk());
        }
    }

    @Test
    void laterCallFailingKeepsPartialResults() throws Exception {
        fakeJSearch(100);
        search();
        mvc.perform(post("/jobs/skip-all"));
        // Next run: first call (pages 1-3, all known) works, second call is rate limited.
        when(client.searchPage(any(), eq("p4"))).thenThrow(new JSearchException("JSearch rate limit hit (HTTP 429)."));
        mvc.perform(post("/search").param("query", "java developer").param("country", "in")
                        .param("numPages", "3").param("minExp", "0").param("maxExp", "2").param("keyword", "java"))
                .andExpect(redirectedUrlPattern("/results?run=*"))
                .andExpect(flash().attribute("error", containsString("Stopped early")));
        assertThat(jobs.count()).isEqualTo(30);
    }
}
