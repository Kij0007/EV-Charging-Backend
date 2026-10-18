package com.evcharging.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;

import tools.jackson.databind.json.JsonMapper;

class SessionAdmissionFilterTest {
    private final MutableClock clock = new MutableClock();
    private final SessionAdmissionLimiter limiter = new SessionAdmissionLimiter(clock, 1, Duration.ofMinutes(5), 10);
    private final SessionAdmissionFilter filter = new SessionAdmissionFilter(limiter,
            JsonMapper.builder().findAndAddModules().build());

    @Test
    void newClientsAreLimitedBeforeSessionCreationAndAddressesStayIndependent() throws Exception {
        MockHttpServletRequest first = request("GET", "198.51.100.1");
        assertThat(allocate(first).getStatus()).isEqualTo(200);
        assertThat(first.getSession(false)).isNotNull();
        MockHttpServletRequest denied = request("GET", "198.51.100.1");
        MockHttpServletResponse response = allocate(denied);
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isNotBlank();
        assertThat(response.getContentAsString()).contains("SESSION_ADMISSION_LIMIT");
        assertThat(denied.getSession(false)).isNull();
        assertThat(allocate(request("GET", "198.51.100.2")).getStatus()).isEqualTo(200);
    }

    @Test
    void existingBrowserSessionBypassesFreshSessionQuota() throws Exception {
        MockHttpServletRequest first = request("GET", "198.51.100.1");
        allocate(first);
        MockHttpServletRequest reuse = request("GET", "198.51.100.1");
        reuse.setSession((MockHttpSession) first.getSession(false));
        assertThat(allocate(reuse).getStatus()).isEqualTo(200);
        assertThat(reuse.getSession(false)).isSameAs(first.getSession(false));
    }

    @Test
    void missingCsrfPostsCannotAllocateUnboundedSessionsBeforeAuthentication() throws Exception {
        CsrfFilter csrf = new CsrfFilter(new HttpSessionCsrfTokenRepository());
        MockHttpServletRequest first = request("POST", "198.51.100.3");
        MockHttpServletResponse initial = new MockHttpServletResponse();
        filter.doFilter(first, initial, (request, response) -> csrf.doFilter(request, response,
                (ignoredRequest, ignoredResponse) -> { throw new AssertionError("CSRF should reject this POST"); }));
        assertThat(initial.getStatus()).isEqualTo(403);
        assertThat(first.getSession(false)).isNotNull();
        MockHttpServletRequest next = request("POST", "198.51.100.3");
        MockHttpServletResponse denied = new MockHttpServletResponse();
        filter.doFilter(next, denied, (request, response) -> csrf.doFilter(request, response,
                (ignoredRequest, ignoredResponse) -> { throw new AssertionError("CSRF should reject this POST"); }));
        assertThat(denied.getStatus()).isEqualTo(429);
        assertThat(next.getSession(false)).isNull();
    }

    @Test
    void expiryAllowsNewAdmissionAndSpoofedForwardHeadersDoNotSelectABucket() throws Exception {
        allocate(request("GET", "198.51.100.4"));
        MockHttpServletRequest spoof = request("GET", "198.51.100.4");
        spoof.addHeader("X-Forwarded-For", "203.0.113.77");
        assertThat(allocate(spoof).getStatus()).isEqualTo(429);
        clock.now = clock.now.plusSeconds(301);
        assertThat(allocate(request("GET", "198.51.100.4")).getStatus()).isEqualTo(200);
    }

    @Test
    void clientMapIsBoundedAndExpiredEntriesReleaseCapacity() {
        SessionAdmissionLimiter small = new SessionAdmissionLimiter(clock, 1, Duration.ofMinutes(5), 1);
        small.admit("client-a");
        assertThatThrownBy(() -> small.admit("client-b")).isInstanceOf(SessionLimitException.class);
        clock.now = clock.now.plusSeconds(301);
        small.admit("client-b");
    }

    private MockHttpServletResponse allocate(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (guarded, ignored) -> {
            ((jakarta.servlet.http.HttpServletRequest) guarded).getSession(true);
            ((jakarta.servlet.http.HttpServletRequest) guarded).getSession(true);
        });
        return response;
    }

    private static MockHttpServletRequest request(String method, String address) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/api/auth/csrf");
        request.setRemoteAddr(address);
        return request;
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
