package com.evcharging.security;

import java.io.IOException;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.evcharging.repository.UserRepository;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Revokes existing sessions on the next request after a password change/account deletion. */
public class SessionAccountFilter extends OncePerRequestFilter {
    private final UserRepository users;

    public SessionAccountFilter(UserRepository users) { this.users = users; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof UserPrincipal principal) {
            boolean valid = users.findById(principal.getUserId()).filter(user ->
                    user.getPasswordHash() != null
                    && user.getCredentialVersion() == principal.getCredentialVersion()
                    && user.getRole().equals(principal.getRole())).isPresent();
            if (!valid) {
                SecurityContextHolder.clearContext();
                if (request.getSession(false) != null) {
                    request.getSession(false).invalidate();
                }
            }
        }
        filterChain.doFilter(request, response);
    }
}
