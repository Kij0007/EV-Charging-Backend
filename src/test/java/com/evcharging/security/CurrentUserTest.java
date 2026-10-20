package com.evcharging.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.evcharging.entity.User;

class CurrentUserTest {
    private final CurrentUser current = new CurrentUser();

    @AfterEach void clearContext() { SecurityContextHolder.clearContext(); }

    @Test
    void ownerCanReadOwnAccountButCannotReadAnother() {
        authenticate("USER");
        assertThat(current.id()).isEqualTo(7L);
        assertThat(current.isAdmin()).isFalse();
        assertThatCode(() -> current.requireOwner(7L)).doesNotThrowAnyException();
        assertThatThrownBy(() -> current.requireOwner(8L)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(current::requireAdmin).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void administratorCanManageAccounts() {
        authenticate("ADMIN");
        assertThatCode(() -> current.requireOwner(8L)).doesNotThrowAnyException();
        assertThatCode(current::requireAdmin).doesNotThrowAnyException();
    }

    @Test
    void missingIdentityCannotBecomeAnImplicitOwner() {
        assertThatThrownBy(current::id).isInstanceOf(AuthenticationCredentialsNotFoundException.class);
    }

    @Test
    void principalErasesPasswordWithoutDisablingEstablishedIdentity() {
        UserPrincipal principal = authenticate("USER");
        assertThat(principal.getPassword()).isNotNull();
        principal.eraseCredentials();
        assertThat(principal.getPassword()).isNull();
        assertThat(principal.isEnabled()).isTrue();
    }

    private UserPrincipal authenticate(String role) {
        User user = new User();
        user.setId(7L); user.setEmail("owner@example.test"); user.setRole(role);
        user.setPasswordHash("encoded-password");
        UserPrincipal principal = new UserPrincipal(user);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
        return principal;
    }
}
