package com.cabaccess;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static com.github.tomakehurst.wiremock.client.WireMock.*;

public final class TestRuntime {
    static final PostgreSQLContainer POSTGRES;
    static final WireMockServer OIDC = new WireMockServer(0);
    static final RSAKey KEY;

    static {
        try {
            POSTGRES = new PostgreSQLContainer("postgres:17.6-alpine").withDatabaseName("cabtest").withUsername("postgres").withPassword("test-bootstrap");
            POSTGRES.start();
            try (var c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()); var s = c.createStatement()) {
                s.execute("CREATE ROLE cab_owner LOGIN PASSWORD 'test-owner' NOSUPERUSER NOBYPASSRLS");
                s.execute("CREATE ROLE cab_app LOGIN PASSWORD 'test-app' NOSUPERUSER NOBYPASSRLS");
                s.execute("GRANT ALL ON SCHEMA public TO cab_owner");
                s.execute("REVOKE CREATE ON SCHEMA public FROM PUBLIC");
            }
            OIDC.start();
            KEY = new RSAKeyGenerator(2048).keyID("test-key").generate();
            OIDC.stubFor(get(urlEqualTo("/jwks")).willReturn(okJson(new JWKSet(KEY.toPublicJWK()).toString())));
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", () -> "cab_app");
        r.add("spring.datasource.password", () -> "test-app");
        r.add("spring.flyway.user", () -> "cab_owner");
        r.add("spring.flyway.password", () -> "test-owner");
        r.add("cab.worker-enabled", () -> false);
        r.add("cab.local-delivery", () -> true);
        r.add("cab.provider", () -> "simulator");
        r.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", OIDC::baseUrl);
        r.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> OIDC.baseUrl() + "/jwks");
        r.add("management.otlp.metrics.export.enabled", () -> false);
        r.add("management.tracing.export.enabled", () -> false);
    }

    @TestConfiguration
    static class TimeConfiguration {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock();
        }
    }

    static class MutableClock extends Clock {
        private volatile Instant now = Instant.parse("2026-09-26T12:00:00Z");

        void at(Instant value) {
            now = value;
        }

        void advance(java.time.Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
