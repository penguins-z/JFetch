package com.fd.jfetch.jsearch;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("jsearch")
/**
 * @param searchPath   search endpoint path ("/search-v2"; the old "/search" no longer exists)
 * @param maxPages     most pages (x10 new jobs) a single search can ask for
 * @param maxScanPages most pages one search may fetch while looking for new jobs. Every page is one
 *                     billed request, so this is the per-search cost cap.
 */
public record JSearchProperties(String apiKey, String host, String baseUrl, String searchPath, int maxPages,
                                int maxScanPages, Duration timeout) {

    public JSearchProperties {
        apiKey = cleanKey(apiKey);
    }

    /** Tolerates the usual .env styles: surrounding quotes and whitespace. */
    static String cleanKey(String key) {
        if (key == null) return null;
        String k = key.trim();
        if (k.length() >= 2 && (k.startsWith("\"") && k.endsWith("\"") || k.startsWith("'") && k.endsWith("'"))) {
            k = k.substring(1, k.length() - 1).trim();
        }
        return k;
    }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }
}
