package com.fd.jfetch.jsearch;

import com.fd.jfetch.job.SearchParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.StringJoiner;

/** Thin client for GET /search on JSearch (OpenWeb Ninja) via RapidAPI. */
@Component
public class JSearchClient {

    private static final Logger log = LoggerFactory.getLogger(JSearchClient.class);

    private final JSearchProperties props;
    private final RestClient restClient;
    private final JsonMapper jsonMapper;

    public JSearchClient(JSearchProperties props, RestClient.Builder builder, JsonMapper jsonMapper) {
        this.props = props;
        this.jsonMapper = jsonMapper;
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(props.timeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(props.timeout());
        this.restClient = builder
                .baseUrl(props.baseUrl())
                .requestFactory(factory)
                .defaultHeader("X-RapidAPI-Host", props.host())
                .build();
    }

    /**
     * One API call = one page (~10 jobs) = one billed request.
     *
     * @param cursor null for the first page, otherwise {@link Page#nextCursor()} from the previous page
     */
    public Page searchPage(SearchParams params, String cursor) {
        if (!props.hasApiKey()) {
            throw new JSearchException("No API key configured. Put RAPIDAPI_KEY=your-key in the .env file next to pom.xml and restart.");
        }
        String datePosted = apiDatePosted(params.datePosted());
        log.info("JSearch request: {} query='{}' date_posted={} country={} cursor={}", props.searchPath(),
                params.query(), datePosted, params.country(), cursor == null ? "-" : "yes");
        String body;
        try {
            body = restClient.get()
                    .uri(b -> {
                        // Values go in as URI variables so they're fully encoded ('+', '&', '/', '=' in a
                        // query or base64 cursor would otherwise be mangled or read as template syntax).
                        Map<String, Object> vars = new HashMap<>();
                        vars.put("query", params.query());
                        vars.put("datePosted", datePosted);
                        b.path(props.searchPath())
                                .queryParam("query", "{query}")
                                .queryParam("num_pages", 1)
                                .queryParam("date_posted", "{datePosted}");
                        if (params.country() != null) {
                            b.queryParam("country", "{country}");
                            vars.put("country", params.country());
                        }
                        if (cursor != null) {
                            b.queryParam("cursor", "{cursor}");
                            vars.put("cursor", cursor);
                        }
                        return b.build(vars);
                    })
                    .header("X-RapidAPI-Key", props.apiKey())
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            String detail = errorDetail(e.getResponseBodyAsString());
            log.warn("JSearch HTTP {} for query='{}': {}", e.getStatusCode().value(), params.query(),
                    e.getResponseBodyAsString());
            throw new JSearchException(describe(e.getStatusCode()) + (detail == null ? "" : " Response: " + detail), e);
        } catch (RestClientException e) {
            throw new JSearchException("Could not reach JSearch: " + e.getMessage(), e);
        }
        return parsePage(body);
    }

    /** search-v2 calls "any time" {@code anytime}; the app stores it as {@code all}. */
    static String apiDatePosted(String datePosted) {
        return datePosted == null || datePosted.equals("all") ? "anytime" : datePosted;
    }

    private static String describe(HttpStatusCode status) {
        return switch (status.value()) {
            case 401, 403 -> "JSearch rejected the request (HTTP " + status.value()
                    + "). Check RAPIDAPI_KEY and that you're subscribed to JSearch by OpenWeb Ninja on RapidAPI.";
            case 429 -> "JSearch rate limit hit (HTTP 429). Wait a while or reuse cached searches.";
            default -> "JSearch returned HTTP " + status.value() + ".";
        };
    }

    /** The "message" from a JSON error body, or the first part of a non-JSON body. */
    private String errorDetail(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            JsonNode root = jsonMapper.readTree(body);
            for (JsonNode n : new JsonNode[]{root.path("message"), root.path("error").path("message"), root.path("error")}) {
                if (n.isString() && !n.asString().isBlank()) return n.asString().trim();
            }
        } catch (JacksonException ignored) {
        }
        String flat = body.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
        return flat.length() > 300 ? flat.substring(0, 300) + "…" : flat;
    }

    /** One page of results; {@code nextCursor} is null when there are no more pages. */
    public record Page(List<JobPosting> jobs, String nextCursor) {
        static final Page EMPTY = new Page(List.of(), null);
    }

    List<JobPosting> parse(String body) {
        return parsePage(body).jobs();
    }

    /**
     * Lenient: skips entries without a job_id and tolerates missing/wrongly-typed fields. Accepts the
     * search-v2 shape {@code data: {jobs: [...], cursor}} and the legacy {@code data: [...]}.
     */
    Page parsePage(String body) {
        if (body == null || body.isBlank()) {
            return Page.EMPTY;
        }
        JsonNode root;
        try {
            root = jsonMapper.readTree(body);
        } catch (JacksonException e) {
            throw new JSearchException("JSearch returned a response that isn't valid JSON.", e);
        }
        String status = text(root, "status");
        if (status != null && !"OK".equalsIgnoreCase(status)) {
            JsonNode err = root.path("error");
            String msg = err.isObject() ? text(err, "message") : text(root, "error");
            throw new JSearchException("JSearch error: " + (msg != null ? msg : status));
        }
        JsonNode data = root.path("data");
        String nextCursor = null;
        if (data.isObject()) {
            nextCursor = text(data, "cursor");
            data = data.path("jobs");
        }
        if (!data.isArray()) {
            return new Page(List.of(), nextCursor);
        }
        List<JobPosting> out = new ArrayList<>();
        int skipped = 0;
        for (JsonNode n : data) {
            String id = text(n, "job_id");
            if (id == null || !n.isObject()) {
                skipped++;
                continue;
            }
            JsonNode exp = n.path("job_required_experience");
            out.add(new JobPosting(
                    id,
                    text(n, "job_title"),
                    text(n, "employer_name"),
                    location(n),
                    firstHttpUrl(text(n, "job_apply_link"), text(n, "job_google_link")),
                    text(n, "job_publisher"),
                    instant(text(n, "job_posted_at_datetime_utc")),
                    text(n, "job_description"),
                    joinStrings(n.path("job_highlights").path("Qualifications")),
                    integer(exp.path("required_experience_in_months")),
                    bool(exp.path("no_experience_required"))));
        }
        if (skipped > 0) {
            log.warn("Skipped {} JSearch entries without a job_id", skipped);
        }
        return new Page(out, nextCursor);
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        if (v.isString() || v.isNumber() || v.isBoolean()) {
            String s = v.asString().trim();
            return s.isEmpty() ? null : s;
        }
        return null;
    }

    private static Integer integer(JsonNode v) {
        if (v.isNumber()) return v.asInt();
        if (v.isString()) {
            try {
                return (int) Math.round(Double.parseDouble(v.asString().trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private static Boolean bool(JsonNode v) {
        if (v.isBoolean()) return v.booleanValue();
        if (v.isString()) {
            String s = v.asString().trim();
            if (s.equalsIgnoreCase("true")) return true;
            if (s.equalsIgnoreCase("false")) return false;
        }
        return null;
    }

    private static Instant instant(String s) {
        if (s == null) return null;
        try {
            return Instant.parse(s);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String joinStrings(JsonNode arr) {
        if (!arr.isArray()) return null;
        StringJoiner j = new StringJoiner("\n");
        for (JsonNode e : arr) {
            if (e.isString()) j.add(e.asString());
        }
        return j.length() == 0 ? null : j.toString();
    }

    private static String location(JsonNode n) {
        String loc = text(n, "job_location");
        if (loc != null) return loc;
        StringJoiner j = new StringJoiner(", ");
        for (String f : new String[]{"job_city", "job_state", "job_country"}) {
            String v = text(n, f);
            if (v != null) j.add(v);
        }
        if (Boolean.TRUE.equals(bool(n.path("job_is_remote")))) j.add("Remote");
        return j.length() == 0 ? null : j.toString();
    }

    /** Only http(s) URLs end up in hrefs; anything else (javascript:, garbage) is dropped. */
    static String firstHttpUrl(String... candidates) {
        for (String c : candidates) {
            if (c == null) continue;
            try {
                URI u = URI.create(c.trim());
                String scheme = u.getScheme();
                if (scheme != null && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https")) && u.getHost() != null) {
                    return u.toString();
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
        return null;
    }
}
