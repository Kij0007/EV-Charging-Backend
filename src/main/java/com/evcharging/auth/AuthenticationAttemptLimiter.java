package com.evcharging.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

/** Bounded, process-local limiter. Multi-instance deployments need an edge/shared limiter. */
@Component
public class AuthenticationAttemptLimiter {
    private static final int MAX_BUCKETS = 10_000;
    private final Clock clock;
    private final Map<String, Bucket> buckets = new HashMap<>();

    public AuthenticationAttemptLimiter() { this(Clock.systemUTC()); }
    AuthenticationAttemptLimiter(Clock clock) { this.clock = clock; }

    public synchronized void check(String operation, String remoteAddress) {
        Instant now = clock.instant();
        buckets.entrySet().removeIf(entry -> !entry.getValue().expiresAt.isAfter(now));
        String key = operation + ':' + remoteAddress;
        Bucket bucket = buckets.get(key);
        if (bucket == null) {
            if (buckets.size() >= MAX_BUCKETS) { throw new TooManyAttemptsException(300); }
            Duration window = "register".equals(operation) ? Duration.ofHours(1) : Duration.ofMinutes(5);
            bucket = new Bucket(now.plus(window));
            buckets.put(key, bucket);
        }
        int limit = "login".equals(operation) ? 10 : 5;
        if (bucket.count >= limit) {
            throw new TooManyAttemptsException(Duration.between(now, bucket.expiresAt).toSeconds() + 1);
        }
        bucket.count++;
    }

    private static final class Bucket {
        private final Instant expiresAt;
        private int count;
        private Bucket(Instant expiresAt) { this.expiresAt = expiresAt; }
    }
}
