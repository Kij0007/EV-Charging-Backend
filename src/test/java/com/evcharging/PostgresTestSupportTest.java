package com.evcharging;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PostgresTestSupportTest {
    @Test
    void acceptsOnlyExplicitlyNamedLocalTestDatabases() {
        assertThatCode(() -> PostgresTestSupport.validateIsolatedDatabaseUrl(
                "jdbc:postgresql://127.0.0.1:55433/evcharging_test_20261017")).doesNotThrowAnyException();
        assertThatCode(() -> PostgresTestSupport.validateIsolatedDatabaseUrl(
                "jdbc:postgresql://127.0.0.1/evcharging_test_ci_1")).doesNotThrowAnyException();
    }

    @Test
    void rejectsApplicationOrRemoteDatabaseTargets() {
        for (String url : new String[] { "jdbc:postgresql://127.0.0.1:55433/evcharging",
                "jdbc:postgresql://localhost:55433/evcharging_test_ci",
                "jdbc:postgresql://192.0.2.10:5432/evcharging_test_ci",
                "jdbc:postgresql://127.0.0.1:55433/evcharging_test_" }) {
            assertThatThrownBy(() -> PostgresTestSupport.validateIsolatedDatabaseUrl(url))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void rejectsUrlOptionsAndOutOfRangePorts() {
        for (String url : new String[] { "jdbc:postgresql://127.0.0.1:55433/evcharging_test_ci?currentSchema=public",
                "jdbc:postgresql://127.0.0.1:99999/evcharging_test_ci",
                "jdbc:postgresql://127.0.0.1:0/evcharging_test_ci", "", "jdbc:h2:mem:tests" }) {
            assertThatThrownBy(() -> PostgresTestSupport.validateIsolatedDatabaseUrl(url))
                    .isInstanceOf(IllegalStateException.class);
        }
    }
}
