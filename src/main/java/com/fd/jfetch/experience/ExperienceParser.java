package com.fd.jfetch.experience;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Works out a job's mandatory experience requirement.
 * <ol>
 *   <li>JSearch's {@code required_experience_in_months} if it's a positive number.</li>
 *   <li>Otherwise parse the title, qualifications and description text, skipping anything optional
 *       (see below). Ranges ("2-4 years") win over minimums ("3+ years"); the first range found is
 *       used (titles come first). Among minimums the largest is used: "5+ years overall, 2+ years
 *       Spring" means you need 5.</li>
 *   <li>Otherwise, JSearch's {@code no_experience_required} flag becomes 0-1 years.</li>
 * </ol>
 * Independently, if the text says freshers are accepted ("freshers can apply", "entry level"...)
 * and doesn't negate it ("freshers need not apply"), the job is flagged fresher-friendly.
 * <p>
 * <b>Optional text</b> is ignored for years: everything under a "Nice to have" / "Good to have" /
 * "Preferred" / "Bonus" / "Desirable" heading (until the next heading), and any sentence containing
 * "is a plus", "preferred", "advantage", "nice to have", etc.
 */
public final class ExperienceParser {

    private static final double MAX_SANE_YEARS = 30;

    private static final String NUM = "(\\d{1,2}(?:\\.\\d)?)";
    private static final String UNIT = "(years?|yrs?)";
    private static final String DASH = "(?:-|–|—|to)";

    // "2-4 years", "2 to 4 yrs", "2+ - 4 years", "2 years - 4 years"
    private static final Pattern RANGE = Pattern.compile(
            "(?<![\\d.])" + NUM + "\\s*(?:\\+|years?|yrs?)?\\s*" + DASH + "\\s*" + NUM + "\\s*\\+?\\s*" + UNIT + "\\b",
            Pattern.CASE_INSENSITIVE);
    // "minimum 3 years", "at least 2 yrs", "min. of 4 years"
    private static final Pattern AT_LEAST = Pattern.compile(
            "(?:minimum|min\\.?|at\\s*least)\\s*(?:of\\s*)?" + NUM + "\\s*\\+?\\s*" + UNIT + "\\b",
            Pattern.CASE_INSENSITIVE);
    // "3+ years", "3 plus years", "3 or more years", "3 years+", "3 years or more", "3 years of experience"
    private static final Pattern SINGLE = Pattern.compile(
            "(?<![\\d.\\-–—])" + NUM + "\\s*(?:\\+|plus|or\\s+more)?\\s*" + UNIT + "\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern FRESHER = Pattern.compile(
            "\\b(freshers?|fresh\\s+graduates?|recent\\s+graduates?|entry[\\s-]level|graduate\\s+trainees?"
                    + "|no\\s+(?:prior\\s+)?experience\\s+(?:is\\s+)?(?:required|needed|necessary))\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NEGATION_BEFORE = Pattern.compile(
            "\\b(no|not|non|excluding|except)\\b[^.;!?\\n]*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern NEGATION_AFTER = Pattern.compile(
            "^[^.;!?\\n]*?\\b(not|cannot|can't|ineligible|need\\s+not|don't|do\\s+not)\\b", Pattern.CASE_INSENSITIVE);

    /** Heading that starts an optional section, e.g. "Nice to have:", "Preferred Qualifications". */
    private static final Pattern OPTIONAL_HEADING = Pattern.compile(
            "^[\\W_]*(nice[\\s-]to[\\s-]have|good[\\s-]to[\\s-]have|preferred|bonus|desirable|desired|optional"
                    + "|added\\s+advantage|plus\\s+points?|additional\\s+(?:skills|qualifications))\\b",
            Pattern.CASE_INSENSITIVE);
    /** Heading that starts a normal (mandatory) section. */
    private static final Pattern HEADING = Pattern.compile(
            "^[\\W_]*(requirements?|required|qualifications?|must[\\s-]haves?|mandatory|minimum\\s+qualifications"
                    + "|basic\\s+qualifications|responsibilities|what\\s+you|who\\s+you|about|key\\s+skills|skills"
                    + "|experience|job\\s+description|role|benefits|perks|education|eligibility)\\b",
            Pattern.CASE_INSENSITIVE);
    /** Marks a single sentence as optional. */
    private static final Pattern OPTIONAL_INLINE = Pattern.compile(
            "\\b(nice[\\s-]to[\\s-]have|good[\\s-]to[\\s-]have|preferred|preferably|ideally|desirable|bonus"
                    + "|optional|not\\s+mandatory|advantage|advantageous|plus\\s+points?)\\b|\\ba\\s+plus\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SENTENCE_END = Pattern.compile("[.;!?](?=\\s)|•");

    private ExperienceParser() {
    }

    /**
     * @param months            JSearch {@code job_required_experience.required_experience_in_months}, may be null
     * @param noExperienceFlag  JSearch {@code job_required_experience.no_experience_required}, may be null
     * @param title             job title; "N years" here counts without needing "experience" nearby
     * @param body              qualifications, description... in priority order; nulls ignored
     */
    public static ExperienceRequirement parse(Integer months, Boolean noExperienceFlag, String title, String... body) {
        ExperienceRequirement result;
        if (months != null && months > 0 && months / 12.0 <= MAX_SANE_YEARS) {
            result = new ExperienceRequirement(round(months / 12.0), null, ExperienceSource.STRUCTURED,
                    "required_experience_in_months=" + months);
        } else {
            result = parseText(title, body);
            if (!result.isKnown() && Boolean.TRUE.equals(noExperienceFlag)) {
                result = new ExperienceRequirement(0.0, 1.0, true, ExperienceSource.STRUCTURED, "no_experience_required=true");
            }
        }
        if (!result.freshersAccepted()) {
            String fresher = findFresher(title, body);
            if (fresher != null) {
                result = result.withFreshers(fresher);
            }
        }
        return result;
    }

    static ExperienceRequirement parseText(String title, String... body) {
        ExperienceRequirement bestMin = null;
        for (int i = -1; i < body.length; i++) {
            String text = i < 0 ? title : body[i];
            boolean isTitle = i < 0;
            if (text == null || text.isBlank()) continue;
            List<int[]> optional = isTitle ? List.of() : optionalRanges(text);

            Matcher r = RANGE.matcher(text);
            while (r.find()) {
                Double a = num(r.group(1)), b = num(r.group(2));
                if (a == null || b == null || a > b || b > MAX_SANE_YEARS) continue;
                if (inRanges(optional, r.start())) continue;
                if (!isTitle && !looksLikeExperience(text, r.start(), r.end(), r.group(3))) continue;
                return new ExperienceRequirement(a, b, ExperienceSource.TEXT, snippet(text, r.start(), r.end()));
            }

            for (Matcher m : List.of(AT_LEAST.matcher(text), SINGLE.matcher(text))) {
                while (m.find()) {
                    Double n = num(m.group(1));
                    if (n == null || n > MAX_SANE_YEARS) continue;
                    if (inRanges(optional, m.start())) continue;
                    if (!isTitle && !looksLikeExperience(text, m.start(), m.end(), m.group(2))) continue;
                    if (bestMin == null || n > bestMin.minYears()) {
                        bestMin = new ExperienceRequirement(n, null, ExperienceSource.TEXT, snippet(text, m.start(), m.end()));
                    }
                }
            }
        }
        return bestMin != null ? bestMin : ExperienceRequirement.UNKNOWN;
    }

    /**
     * Character ranges of {@code text} that are optional: lines under an optional heading (until the
     * next heading) plus individual sentences with optional wording.
     */
    static List<int[]> optionalRanges(String text) {
        List<int[]> out = new ArrayList<>();
        boolean inOptionalSection = false;
        int lineStart = 0;
        while (lineStart <= text.length()) {
            int nl = text.indexOf('\n', lineStart);
            int lineEnd = nl < 0 ? text.length() : nl;
            String line = text.substring(lineStart, lineEnd).trim();
            if (!line.isEmpty()) {
                if (isHeading(line, OPTIONAL_HEADING)) {
                    if (hasInlineContent(line)) {
                        // "Good to have: 6 years of Go. Minimum 2 years Java." -> only the first sentence
                        inOptionalSection = false;
                        int firstEnd = firstSentenceEnd(text, lineStart, lineEnd);
                        out.add(new int[]{lineStart, firstEnd});
                        addOptionalSentences(text, firstEnd, lineEnd, out);
                    } else {
                        inOptionalSection = true;
                    }
                } else if (isHeading(line, HEADING)) {
                    inOptionalSection = false;
                }
                if (inOptionalSection) {
                    out.add(new int[]{lineStart, lineEnd});
                } else if (!isHeading(line, OPTIONAL_HEADING)) {
                    addOptionalSentences(text, lineStart, lineEnd, out);
                }
            }
            if (nl < 0) break;
            lineStart = nl + 1;
        }
        return out;
    }

    /**
     * A heading starts with the keyword and is either short with no digits, or has a colon right
     * after it. "Nice to have: 3 years of Kafka" and "Requirements" count; a bullet like
     * "Experience with Kafka (2 years)" doesn't.
     */
    private static boolean isHeading(String line, Pattern keyword) {
        Matcher m = keyword.matcher(line);
        if (!m.find()) return false;
        if (line.length() <= 40 && line.chars().noneMatch(Character::isDigit)) return true;
        int colon = line.indexOf(':');
        return colon >= 0 && colon <= m.end() + 25;
    }

    private static boolean hasInlineContent(String line) {
        int colon = line.indexOf(':');
        return colon >= 0 && !line.substring(colon + 1).isBlank();
    }

    private static int firstSentenceEnd(String text, int from, int to) {
        Matcher end = SENTENCE_END.matcher(text).region(from, to);
        return end.find() ? end.end() : to;
    }

    private static void addOptionalSentences(String text, int from, int to, List<int[]> out) {
        Matcher end = SENTENCE_END.matcher(text).region(from, to);
        int start = from;
        while (true) {
            int stop = end.find() ? end.end() : to;
            if (OPTIONAL_INLINE.matcher(text.substring(start, stop)).find()) {
                out.add(new int[]{start, stop});
            }
            if (stop >= to) break;
            start = stop;
        }
    }

    private static boolean inRanges(List<int[]> ranges, int pos) {
        for (int[] r : ranges) {
            if (pos >= r[0] && pos < r[1]) return true;
        }
        return false;
    }

    /** First non-negated fresher mention anywhere (optional sections included), as a snippet. */
    static String findFresher(String title, String... body) {
        for (int i = -1; i < body.length; i++) {
            String text = i < 0 ? title : body[i];
            if (text == null) continue;
            Matcher m = FRESHER.matcher(text);
            while (m.find()) {
                String before = text.substring(Math.max(0, m.start() - 40), m.start());
                String after = text.substring(m.end(), Math.min(text.length(), m.end() + 40));
                if (NEGATION_BEFORE.matcher(before).find() || NEGATION_AFTER.matcher(after).find()) continue;
                return snippet(text, m.start(), m.end());
            }
        }
        return null;
    }

    /**
     * "yrs" is almost always about experience in job ads. For "years" we need "exp..." nearby,
     * otherwise phrases like "a 4 years degree" or "founded 20 years ago" would count. Titles skip
     * this check: "Java Developer (0-1 years)" is always about experience.
     */
    private static boolean looksLikeExperience(String text, int start, int end, String unit) {
        if (unit != null && unit.toLowerCase(Locale.ROOT).startsWith("yr")) {
            return true;
        }
        int from = Math.max(0, start - 40);
        int to = Math.min(text.length(), end + 60);
        return text.substring(from, to).toLowerCase(Locale.ROOT).contains("exp");
    }

    private static Double num(String s) {
        try {
            return s == null ? null : Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static double round(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private static String snippet(String text, int start, int end) {
        int from = Math.max(0, start - 30);
        int to = Math.min(text.length(), end + 30);
        return (from > 0 ? "…" : "") + text.substring(from, to).replaceAll("\\s+", " ").trim() + (to < text.length() ? "…" : "");
    }
}
