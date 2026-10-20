package com.evcharging.security;

import java.io.IOException;

import org.apache.catalina.session.TooManyActiveSessionsException;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import tools.jackson.databind.ObjectMapper;

/** Runs before CSRF can allocate a session, including on requests that CSRF rejects. */
public class SessionAdmissionFilter extends OncePerRequestFilter {
    private final SessionAdmissionLimiter limiter;
    private final ObjectMapper mapper;

    public SessionAdmissionFilter(SessionAdmissionLimiter limiter, ObjectMapper mapper) {
        this.limiter = limiter;
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        HttpServletRequest guarded = new HttpServletRequestWrapper(request) {
            private boolean admitted;

            @Override public HttpSession getSession() { return getSession(true); }

            @Override
            public HttpSession getSession(boolean create) {
                HttpSession existing = super.getSession(false);
                if (existing != null || !create) { return existing; }
                if (!admitted) {
                    // getRemoteAddr is rewritten only by a configured, trusted container valve.
                    limiter.admit(getRemoteAddr());
                    admitted = true;
                }
                try {
                    return super.getSession(true);
                } catch (TooManyActiveSessionsException capacity) {
                    throw SessionLimitException.capacity();
                }
            }
        };
        try {
            chain.doFilter(guarded, response);
        } catch (SessionLimitException exception) {
            response.setStatus(exception.getStatus());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setHeader("Retry-After", Long.toString(exception.getRetryAfterSeconds()));
            mapper.writeValue(response.getOutputStream(), SessionLimitExceptionHandler.error(exception, request));
        }
    }
}
