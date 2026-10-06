package com.fd.jfetch.jsearch;

import com.fd.jfetch.job.SearchParams;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JSearchClientHttpTest {

    private HttpServer server;

    private JSearchClient start(int status, String body, AtomicReference<String> seenQuery) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            seenQuery.set(ex.getRequestURI().getRawPath() + "?" + ex.getRequestURI().getRawQuery());
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
        return new JSearchClient(new JSearchProperties("k", "jsearch.p.rapidapi.com",
                "http://127.0.0.1:" + server.getAddress().getPort(), "/search-v2", 10, 15, Duration.ofSeconds(5)),
                RestClient.builder(), JsonMapper.builder().build());
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void errorMessageFromBodyIsShown() throws Exception {
        var seen = new AtomicReference<String>();
        JSearchClient client = start(404, "{\"message\":\"Endpoint '/search' does not exist\"}", seen);
        assertThatThrownBy(() -> client.searchPage(new SearchParams("java", "in", "all", 1), null))
                .isInstanceOf(JSearchException.class)
                .hasMessageContaining("HTTP 404")
                .hasMessageContaining("Endpoint '/search' does not exist");
        assertThat(seen.get()).startsWith("/search-v2?query=java&num_pages=1&date_posted=anytime&country=in");
        assertThat(seen.get()).doesNotContain("cursor");
    }

    @Test
    void cursorAndQueryAreFullyEncoded() throws Exception {
        var seen = new AtomicReference<String>();
        JSearchClient client = start(200, "{\"status\":\"OK\",\"data\":{\"jobs\":[{\"job_id\":\"a\"}],\"cursor\":\"n2\"}}", seen);
        var page = client.searchPage(new SearchParams("c++ & java {dev}", null, "week", 1), "ab+c/d=");
        assertThat(page.nextCursor()).isEqualTo("n2");
        assertThat(page.jobs()).hasSize(1);
        assertThat(seen.get()).contains("query=c%2B%2B%20%26%20java%20%7Bdev%7D")
                .contains("date_posted=week")
                .contains("cursor=ab%2Bc%2Fd%3D");
    }

    @Test
    void nonJsonErrorBodyIsTruncatedText() throws Exception {
        JSearchClient client = start(404, "<html><body><h1>Not Found</h1></body></html>", new AtomicReference<>());
        assertThatThrownBy(() -> client.searchPage(new SearchParams("java", null, "all", 1), null))
                .hasMessageContaining("Response: Not Found");
    }
}
