package com.fd.jfetch.experience;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExperienceParserTest {

    /** Body text only (no title). */
    private static ExperienceRequirement text(String... t) {
        return ExperienceParser.parse(null, null, null, t);
    }

    @Test
    void structuredMonthsWin() {
        var r = ExperienceParser.parse(36, false, "5-8 years of experience");
        assertThat(r.source()).isEqualTo(ExperienceSource.STRUCTURED);
        assertThat(r.minYears()).isEqualTo(3.0);
        assertThat(r.maxYears()).isNull();
    }

    @Test
    void parsesRanges() {
        assertThat(text("We need 2-4 years of experience in Java").minYears()).isEqualTo(2.0);
        assertThat(text("We need 2-4 years of experience in Java").maxYears()).isEqualTo(4.0);
        assertThat(text("Experience: 1 to 3 years").maxYears()).isEqualTo(3.0);
        assertThat(text("Java Developer (0–2 yrs)").maxYears()).isEqualTo(2.0);
        assertThat(text("3 years - 5 years experience").minYears()).isEqualTo(3.0);
    }

    @Test
    void yearsAndYrsBothWork() {
        assertThat(text("Freshers welcome. 0-1 years of experience").maxYears()).isEqualTo(1.0);
        assertThat(text("Experience: 0-1 yrs").maxYears()).isEqualTo(1.0);
        // In a title, "years" counts even without "experience" next to it...
        assertThat(ExperienceParser.parse(null, null, "Java Developer (0-1 years)").maxYears()).isEqualTo(1.0);
        // ...but in body text it still needs it.
        assertThat(ExperienceParser.parse(null, null, "Java Developer", "Contract for 0-1 years.").isKnown()).isFalse();
    }

    @Test
    void parsesMinimumsAndTakesLargest() {
        var r = text("5+ years of experience overall, 2+ years experience with Spring");
        assertThat(r.minYears()).isEqualTo(5.0);
        assertThat(r.maxYears()).isNull();
        assertThat(text("Minimum 3 years experience").minYears()).isEqualTo(3.0);
        assertThat(text("at least 4 yrs in backend").minYears()).isEqualTo(4.0);
    }

    @Test
    void ignoresYearsNotAboutExperience() {
        assertThat(text("We were founded 20 years ago. Bachelor's degree (4 years).").isKnown()).isFalse();
    }

    @Test
    void fresherAndNoExperienceFlag() {
        assertThat(text("Freshers welcome!").maxYears()).isEqualTo(1.0);
        var flagged = ExperienceParser.parse(null, true, "Great Java role");
        assertThat(flagged.source()).isEqualTo(ExperienceSource.STRUCTURED);
        assertThat(flagged.maxYears()).isEqualTo(1.0);
        // Text beats the often-wrong no_experience_required flag
        assertThat(ExperienceParser.parse(null, true, "3+ years experience").minYears()).isEqualTo(3.0);
    }

    @Test
    void freshersAcceptedOverridesStatedYears() {
        var r = text("3+ years of experience required. Freshers can also apply.");
        assertThat(r.minYears()).isEqualTo(3.0);
        assertThat(r.freshersAccepted()).isTrue();
        assertThat(r.overlaps(0, 1)).isTrue();
        assertThat(text("Open to fresh graduates").overlaps(0, 1)).isTrue();
        assertThat(ExperienceParser.parse(null, null, "Java Developer - Entry Level").freshersAccepted()).isTrue();
        // Even with JSearch's structured months
        assertThat(ExperienceParser.parse(36, null, "Java Dev", "Freshers welcome").overlaps(0, 2)).isTrue();
    }

    @Test
    void negatedFresherMentionsDontCount() {
        assertThat(text("3+ years experience. Freshers need not apply.").freshersAccepted()).isFalse();
        assertThat(text("3+ years experience. Not suitable for freshers.").freshersAccepted()).isFalse();
        assertThat(text("3+ years experience. No freshers please.").freshersAccepted()).isFalse();
        // A negation in the next sentence doesn't affect it
        assertThat(text("Freshers can apply. Do not miss this chance.").freshersAccepted()).isTrue();
    }

    @Test
    void niceToHaveSectionIsIgnored() {
        String desc = """
                Requirements:
                - 1-2 years of experience in Java
                - Spring Boot
                Nice to have:
                - 5+ years of experience with Kafka
                - Experience with Kubernetes (3 years)
                Benefits
                - Health insurance
                """;
        var r = text(desc);
        assertThat(r.minYears()).isEqualTo(1.0);
        assertThat(r.maxYears()).isEqualTo(2.0);

        // Minimums: the optional 5+ must not beat the mandatory 2+
        var m = text("""
                What you need
                • 2+ years of Java experience
                Preferred Qualifications
                • 5+ years of experience in fintech
                """);
        assertThat(m.minYears()).isEqualTo(2.0);
    }

    @Test
    void inlineOptionalSentencesAreIgnored() {
        assertThat(text("2+ years of experience in Java. 4+ years of experience with AWS is a plus.").minYears())
                .isEqualTo(2.0);
        assertThat(text("Must have 1 year experience in Java; 3 years experience in React preferred.").minYears())
                .isEqualTo(1.0);
        assertThat(text("Good to have: 6 years of experience in Go. Minimum 2 years experience in Java.").minYears())
                .isEqualTo(2.0);
        // Only optional mentions -> no mandatory requirement found
        assertThat(text("Experience with Kafka (3 years experience) would be an advantage.").isKnown()).isFalse();
    }

    @Test
    void headingResetsOptionalSection() {
        var r = text("""
                Bonus:
                - 8 years of experience in Scala
                Required skills:
                - 3+ years of experience in Java
                """);
        assertThat(r.minYears()).isEqualTo(3.0);
    }

    @Test
    void unknownWhenNothingFound() {
        assertThat(ExperienceParser.parse(null, null, null, "", "Nice team").isKnown()).isFalse();
    }

    @Test
    void overlapSemantics() {
        var range = new ExperienceRequirement(1.0, 3.0, ExperienceSource.TEXT, "");
        assertThat(range.overlaps(0, 2)).isTrue();
        assertThat(range.overlaps(4, 6)).isFalse();
        var plus = new ExperienceRequirement(5.0, null, ExperienceSource.TEXT, "");
        assertThat(plus.overlaps(0, 2)).isFalse();
        assertThat(plus.overlaps(5, 7)).isTrue();
    }
}
