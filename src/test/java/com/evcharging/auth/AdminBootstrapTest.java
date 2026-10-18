package com.evcharging.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.evcharging.entity.User;
import com.evcharging.repository.UserRepository;

import jakarta.validation.Validator;

@ExtendWith(MockitoExtension.class)
class AdminBootstrapTest {
    @Mock private UserRepository users;
    @Mock private PasswordEncoder encoder;
    @Mock private Validator validator;

    @Test
    void absentConfigurationNeverCreatesAnAdministrator() {
        new AdminBootstrap(users, encoder, validator, "", "").run(null);
        verifyNoInteractions(users, encoder, validator);
    }

    @Test
    void bootstrapDoesNotPromoteOrReplaceAnExistingAccount() {
        when(validator.validate(any(RegisterRequest.class))).thenReturn(Set.of());
        when(users.existsByEmailIgnoreCase("existing@example.test")).thenReturn(true);
        new AdminBootstrap(users, encoder, validator,
                "Existing@Example.Test", "long-enough-password").run(null);
        verify(users, never()).saveAndFlush(any());
        verifyNoInteractions(encoder);
    }

    @Test
    void explicitConfigurationCreatesHashedAdminWithNormalizedEmail() {
        when(validator.validate(any(RegisterRequest.class))).thenReturn(Set.of());
        when(encoder.encode("long-enough-password")).thenReturn("encoded-password");
        new AdminBootstrap(users, encoder, validator,
                "ADMIN@Example.Test", "long-enough-password").run(null);
        ArgumentCaptor<User> captured = ArgumentCaptor.forClass(User.class);
        verify(users).saveAndFlush(captured.capture());
        assertThat(captured.getValue().getEmail()).isEqualTo("admin@example.test");
        assertThat(captured.getValue().getRole()).isEqualTo("ADMIN");
        assertThat(captured.getValue().getPasswordHash()).isEqualTo("encoded-password");
    }
}
