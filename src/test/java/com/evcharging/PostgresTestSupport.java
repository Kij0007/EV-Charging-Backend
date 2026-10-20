package com.evcharging;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Optional PostgreSQL verification, restricted to an explicitly named local test database. */
public abstract class PostgresTestSupport {
    private static final Pattern ISOLATED_DATABASE = Pattern.compile(
            "^jdbc:postgresql://127\\.0\\.0\\.1(?::([1-9][0-9]{0,4}))?/evcharging_test_[A-Za-z0-9_]+$");

    @DynamicPropertySource
    public static void postgresProperties(DynamicPropertyRegistry registry) {
        String url = System.getenv("EV_TEST_POSTGRES_URL");
        if (url == null) {
            return; // Keep the isolated H2 defaults when PostgreSQL was not requested.
        }
        validateIsolatedDatabaseUrl(url);
        String username = requiredEnvironment("EV_TEST_POSTGRES_USERNAME");
        String password = requiredEnvironment("EV_TEST_POSTGRES_PASSWORD");
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> username);
        registry.add("spring.datasource.password", () -> password);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        // The test profile otherwise explicitly selects H2's dialect.
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 4);
        registry.add("spring.datasource.hikari.minimum-idle", () -> 1);
    }

    static void validateIsolatedDatabaseUrl(String url) {
        Matcher matcher = ISOLATED_DATABASE.matcher(url == null ? "" : url);
        if (!matcher.matches() || (matcher.group(1) != null && Integer.parseInt(matcher.group(1)) > 65535)) {
            throw new IllegalStateException("PostgreSQL tests require a 127.0.0.1 URL and a database named "
                    + "evcharging_test_<suffix>, with no URL parameters");
        }
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be nonblank when PostgreSQL tests are enabled");
        }
        return value;
    }
}
