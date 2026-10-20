package com.evcharging.security;

/** A bounded, public-safe failure during allocation of a new browser session. */
public final class SessionLimitException extends RuntimeException {
    private final int status;
    private final String code;
    private final long retryAfterSeconds;

    private SessionLimitException(int status, String code, String message, long retryAfterSeconds) {
        super(message);
        this.status = status;
        this.code = code;
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    static SessionLimitException throttled(long retryAfterSeconds) {
        return new SessionLimitException(429, "SESSION_ADMISSION_LIMIT",
                "Too many new sessions; reuse your current browser session or try again later", retryAfterSeconds);
    }

    static SessionLimitException capacity() {
        return new SessionLimitException(503, "SESSION_CAPACITY_REACHED",
                "The website is busy; please try again later", 60);
    }

    public int getStatus() { return status; }
    public String getCode() { return code; }
    public long getRetryAfterSeconds() { return retryAfterSeconds; }
}
