package com.evcharging.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Limits new sessions rather than normal requests from an existing browser. */
@Component
public class SessionAdmissionLimiter {
    private final Clock clock;
    private final int maximum;
    private final Duration window;
    private final int maxClients;
    private final Map<String, Bucket> clients = new HashMap<>();

    @Autowired
    public SessionAdmissionLimiter(
            @Value("${app.security.session-admission.max-per-window:30}") int maximum,
            @Value("${app.security.session-admission.window-seconds:300}") long windowSeconds,
            @Value("${app.security.session-admission.max-clients:10000}") int maxClients) {
        this(Clock.systemUTC(), maximum, Duration.ofSeconds(windowSeconds), maxClients);
    }

    SessionAdmissionLimiter(Clock clock, int maximum, Duration window, int maxClients) {
        if (maximum <= 0 || window.isZero() || window.isNegative() || maxClients <= 0) {
            throw new IllegalArgumentException("Session admission limits must be positive");
        }
        this.clock = clock;
        this.maximum = maximum;
        this.window = window;
        this.maxClients = maxClients;
    }

    public synchronized void admit(String remoteAddress) {
        Instant now = clock.instant();
        clients.entrySet().removeIf(entry -> !entry.getValue().expires.isAfter(now));
        Bucket bucket = clients.get(remoteAddress);
        if (bucket == null) {
            if (clients.size() >= maxClients) {
                throw SessionLimitException.throttled(window.toSeconds());
            }
            bucket = new Bucket(now.plus(window));
            clients.put(remoteAddress, bucket);
        }
        if (bucket.count >= maximum) {
            throw SessionLimitException.throttled(Duration.between(now, bucket.expires).toSeconds() + 1);
        }
        bucket.count++;
    }

    private static final class Bucket {
        private final Instant expires;
        private int count;
        private Bucket(Instant expires) { this.expires = expires; }
    }
}
