package com.evcharging.auth;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class AuthenticationAttemptLimiterTest {
    @Test
    void loginLimitAppliesPerAddressAndExpires() {
        MutableClock clock = new MutableClock();
        AuthenticationAttemptLimiter limiter = new AuthenticationAttemptLimiter(clock);
        for (int i = 0; i < 10; i++) { limiter.check("login", "client-a"); }
        assertThatThrownBy(() -> limiter.check("login", "client-a"))
                .isInstanceOf(TooManyAttemptsException.class);
        assertThatCode(() -> limiter.check("login", "client-b")).doesNotThrowAnyException();
        clock.now = clock.now.plusSeconds(301);
        assertThatCode(() -> limiter.check("login", "client-a")).doesNotThrowAnyException();
    }

    @Test
    void registrationHasAnIndependentStricterWindow() {
        MutableClock clock = new MutableClock();
        AuthenticationAttemptLimiter limiter = new AuthenticationAttemptLimiter(clock);
        for (int i = 0; i < 5; i++) { limiter.check("register", "client"); }
        assertThatThrownBy(() -> limiter.check("register", "client"))
                .isInstanceOf(TooManyAttemptsException.class);
        assertThatCode(() -> limiter.check("login", "client")).doesNotThrowAnyException();
        clock.now = clock.now.plusSeconds(301);
        assertThatThrownBy(() -> limiter.check("register", "client"))
                .isInstanceOf(TooManyAttemptsException.class);
        clock.now = clock.now.plusSeconds(3300);
        assertThatCode(() -> limiter.check("register", "client")).doesNotThrowAnyException();
    }

    @Test
    void bcryptByteLimitPreventsTruncationAndUnicodeOverflow() {
        assertThatCode(() -> PasswordPolicy.validate("a".repeat(72))).doesNotThrowAnyException();
        assertThatThrownBy(() -> PasswordPolicy.validate("a".repeat(73)))
                .hasMessageContaining("72 UTF-8 bytes");
        assertThatThrownBy(() -> PasswordPolicy.validate("🔌".repeat(19)))
                .hasMessageContaining("72 UTF-8 bytes");
        assertThatThrownBy(() -> PasswordPolicy.validate("short"))
                .hasMessageContaining("12 characters");
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
