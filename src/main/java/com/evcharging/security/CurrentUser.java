package com.evcharging.security;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class CurrentUser {
    public UserPrincipal principal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof UserPrincipal principal)) {
            throw new AuthenticationCredentialsNotFoundException("Sign in to continue");
        }
        return principal;
    }

    public Long id() { return principal().getUserId(); }
    public boolean isAdmin() { return "ADMIN".equals(principal().getRole()); }

    public void requireOwner(Long userId) {
        if (!isAdmin() && !id().equals(userId)) {
            throw new AccessDeniedException("You cannot access another customer's account or bookings");
        }
    }

    public void requireAdmin() {
        if (!isAdmin()) {
            throw new AccessDeniedException("Administrator access is required");
        }
    }
}
