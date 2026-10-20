package com.evcharging.security;

import java.util.Collection;
import java.util.List;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import com.evcharging.entity.User;

/** The session stores identity and a credential version; it never serializes a JPA entity. */
public final class UserPrincipal implements UserDetails, CredentialsContainer {
    private static final long serialVersionUID = 1L;
    private final Long userId;
    private final String email;
    private final String role;
    private final int credentialVersion;
    private final boolean enabled;
    private transient String passwordHash;

    public UserPrincipal(User user) {
        this.userId = user.getId();
        this.email = user.getEmail();
        this.role = user.getRole();
        this.passwordHash = user.getPasswordHash();
        this.enabled = passwordHash != null && !passwordHash.isBlank();
        this.credentialVersion = user.getCredentialVersion();
    }

    public Long getUserId() { return userId; }
    public String getRole() { return role; }
    public int getCredentialVersion() { return credentialVersion; }
    public void erasePassword() { this.passwordHash = null; }
    @Override public void eraseCredentials() { erasePassword(); }
    @Override public String getUsername() { return email; }
    @Override public String getPassword() { return passwordHash; }
    @Override public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role));
    }
    @Override public boolean isEnabled() { return enabled; }
}
