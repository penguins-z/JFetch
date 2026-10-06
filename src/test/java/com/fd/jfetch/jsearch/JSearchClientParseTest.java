package com.fd.jfetch.jsearch;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JSearchClientParseTest {

    private final JSearchClient client = new JSearchClient(
            new JSearchProperties("k", "jsearch.p.rapidapi.com", "https://jsearch.p.rapidapi.com", "/search-v2", 10, 15, Duration.ofSeconds(5)),
            RestClient.builder(), JsonMapper.builder().build());

    @Test
    void toleratesMalformedAndMissingFields() {
        String body = """
                {"status":"OK","data":[
                  {"job_id":"a==","job_title":"Java Dev","employer_name":"Acme",
                   "job_apply_link":"javascript:alert(1)","job_google_link":"https://google.com/x",
                   "job_posted_at_datetime_utc":"not a date",
                   "job_required_experience":{"required_experience_in_months":"24","no_experience_required":"false"},
                   "job_highlights":{"Qualifications":["3+ years Java", 5, null]}},
                  {"job_title":"no id, skipped"},
                  "garbage",
                  {"job_id":"b","job_required_experience":null,"job_highlights":"oops","job_city":"Pune","job_country":"IN"}
                ]}
                """;
        var postings = client.parse(body);
        assertThat(postings).hasSize(2);
        var a = postings.get(0);
        assertThat(a.applyUrl()).isEqualTo("https://google.com/x");
        assertThat(a.postedAt()).isNull();
        assertThat(a.requiredExperienceMonths()).isEqualTo(24);
        assertThat(a.noExperienceRequired()).isFalse();
        assertThat(a.qualifications()).isEqualTo("3+ years Java");
        var b = postings.get(1);
        assertThat(b.title()).isNull();
        assertThat(b.location()).isEqualTo("Pune, IN");
        assertThat(b.applyUrl()).isNull();
    }

    @Test
    void searchV2ShapeWithCursor() {
        var page = client.parsePage("""
                {"status":"OK","request_id":"r","data":{"jobs":[{"job_id":"x","job_title":"Java Dev"}],"cursor":"abc+/="}}
                """);
        assertThat(page.jobs()).extracting(JobPosting::jobId).containsExactly("x");
        assertThat(page.nextCursor()).isEqualTo("abc+/=");
        var last = client.parsePage("{\"status\":\"OK\",\"data\":{\"jobs\":[]}}");
        assertThat(last.jobs()).isEmpty();
        assertThat(last.nextCursor()).isNull();
        assertThat(client.parsePage("{\"status\":\"OK\",\"data\":{}}").jobs()).isEmpty();
    }

    @Test
    void emptyAndMissingData() {
        assertThat(client.parse("")).isEmpty();
        assertThat(client.parse("{\"status\":\"OK\",\"data\":[]}")).isEmpty();
        assertThat(client.parse("{\"status\":\"OK\"}")).isEmpty();
    }

    @Test
    void errorStatusAndInvalidJson() {
        assertThatThrownBy(() -> client.parse("{\"status\":\"ERROR\",\"error\":{\"message\":\"bad query\"}}"))
                .isInstanceOf(JSearchException.class).hasMessageContaining("bad query");
        assertThatThrownBy(() -> client.parse("<html>")).isInstanceOf(JSearchException.class);
    }
}
