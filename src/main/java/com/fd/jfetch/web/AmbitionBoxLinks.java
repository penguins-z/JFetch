package com.fd.jfetch.web;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Builds AmbitionBox URLs from a company name. Link construction only: nothing here (or anywhere
 * in this app) fetches AmbitionBox.
 */
public final class AmbitionBoxLinks {

    private static final String OVERVIEW = "https://www.ambitionbox.com/overview/%s-overview";
    private static final String SEARCH = "https://www.ambitionbox.com/search?query=%s";

    private static final Set<String> LEGAL_SUFFIXES = Set.of(
            "pvt", "private", "ltd", "limited", "inc", "incorporated", "llc", "llp", "plc",
            "corp", "co", "gmbh", "ag", "sa", "bv", "pte");
    /** Names made only of these characters are considered safe to slugify. */
    private static final Pattern CLEAN_NAME = Pattern.compile("[A-Za-z0-9 &.,'()\\-]+");

    public record Link(String url, boolean isSearch) {
    }

    private AmbitionBoxLinks() {
    }

    /** Overview URL when the name slugifies cleanly, otherwise a search URL. Null if no name. */
    public static Link forCompany(String companyName) {
        if (companyName == null || companyName.isBlank()) {
            return null;
        }
        String slug = slug(companyName);
        if (slug != null) {
            return new Link(OVERVIEW.formatted(slug), false);
        }
        return new Link(searchUrl(companyName), true);
    }

    public static String searchUrl(String companyName) {
        return SEARCH.formatted(URLEncoder.encode(companyName.trim(), StandardCharsets.UTF_8));
    }

    /** e.g. "Tata Consultancy Services Pvt. Ltd." -> "tata-consultancy-services"; null if not clean. */
    static String slug(String companyName) {
        String name = Normalizer.normalize(companyName.trim(), Normalizer.Form.NFKD).replaceAll("\\p{M}", "");
        if (!CLEAN_NAME.matcher(name).matches()) {
            return null;
        }
        name = name.replaceAll("\\([^)]*\\)", " ").replace("'", "");
        List<String> tokens = new java.util.ArrayList<>(Arrays.asList(
                name.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")));
        tokens.removeIf(String::isEmpty);
        while (tokens.size() > 1 && LEGAL_SUFFIXES.contains(tokens.getLast())) {
            tokens.removeLast();
        }
        return tokens.isEmpty() ? null : String.join("-", tokens);
    }
}
