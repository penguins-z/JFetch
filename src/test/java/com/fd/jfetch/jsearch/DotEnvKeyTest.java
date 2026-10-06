package com.fd.jfetch.jsearch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DotEnvKeyTest {

    @Configuration
    @EnableConfigurationProperties(JSearchProperties.class)
    static class Cfg {
    }

    /** Boots just the config layer with the real application.properties, pointing the import at a temp .env. */
    private JSearchProperties load(Path envFile, Map<String, Object> extra) {
        SpringApplication app = new SpringApplication(Cfg.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        var props = new java.util.HashMap<String, Object>(extra);
        props.put("spring.config.import", "optional:file:" + envFile + "[.properties]");
        app.setDefaultProperties(props);
        try (ConfigurableApplicationContext ctx = app.run()) {
            return ctx.getBean(JSearchProperties.class);
        }
    }

    @Test
    void keyIsReadFromDotEnv(@TempDir Path dir) throws Exception {
        Path env = dir.resolve(".env");
        Files.writeString(env, "# comment\nRAPIDAPI_KEY=\"abc123\"\n");
        assertThat(load(env, Map.of()).apiKey()).isEqualTo("abc123");
    }

    @Test
    void missingDotEnvIsFine(@TempDir Path dir) {
        assertThat(load(dir.resolve("nope.env"), Map.of()).hasApiKey()).isFalse();
    }

    @Test
    void cleanKeyStripsQuotes() {
        assertThat(JSearchProperties.cleanKey("  'k1'  ")).isEqualTo("k1");
        assertThat(JSearchProperties.cleanKey("\"k2\"")).isEqualTo("k2");
        assertThat(JSearchProperties.cleanKey("k3")).isEqualTo("k3");
    }
}
