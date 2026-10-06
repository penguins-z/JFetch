package com.fd.jfetch.web;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AmbitionBoxLinksTest {

    @Test
    void buildsOverviewSlug() {
        assertThat(AmbitionBoxLinks.forCompany("Tata Consultancy Services Pvt. Ltd.").url())
                .isEqualTo("https://www.ambitionbox.com/overview/tata-consultancy-services-overview");
        assertThat(AmbitionBoxLinks.forCompany("Infosys Limited").url())
                .isEqualTo("https://www.ambitionbox.com/overview/infosys-overview");
        assertThat(AmbitionBoxLinks.forCompany("Larsen & Toubro").url())
                .isEqualTo("https://www.ambitionbox.com/overview/larsen-toubro-overview");
        assertThat(AmbitionBoxLinks.forCompany("Société Générale").url())
                .isEqualTo("https://www.ambitionbox.com/overview/societe-generale-overview");
    }

    @Test
    void fallsBackToSearchForUncleanNames() {
        var link = AmbitionBoxLinks.forCompany("株式会社 Foo");
        assertThat(link.isSearch()).isTrue();
        assertThat(link.url()).startsWith("https://www.ambitionbox.com/search?query=");
        assertThat(AmbitionBoxLinks.forCompany("Acme@Corp").isSearch()).isTrue();
    }

    @Test
    void nullForMissingName() {
        assertThat(AmbitionBoxLinks.forCompany(null)).isNull();
        assertThat(AmbitionBoxLinks.forCompany("  ")).isNull();
    }
}
