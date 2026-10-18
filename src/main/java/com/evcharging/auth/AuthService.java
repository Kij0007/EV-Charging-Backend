package com.evcharging.auth;

import java.util.Locale;
import java.nio.charset.StandardCharsets;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.evcharging.entity.User;
import com.evcharging.exception.BadRequestException;
import com.evcharging.exception.DomainConflictException;
import com.evcharging.exception.UserNotFoundException;
import com.evcharging.repository.UserRepository;

@Service
public class AuthService {
    private final UserRepository users;
    private final PasswordEncoder encoder;

    public AuthService(UserRepository users, PasswordEncoder encoder) {
        this.users = users;
        this.encoder = encoder;
    }

    @Transactional
    public AuthUserDTO register(RegisterRequest request) {
        PasswordPolicy.validate(request.password());
        String email = request.email().strip().toLowerCase(Locale.ROOT);
        if (users.existsByEmailIgnoreCase(email)) {
            throw new DomainConflictException("An account cannot be registered with those details");
        }
        User user = new User();
        user.setName(request.name().strip());
        user.setEmail(email);
        user.setPasswordHash(encoder.encode(request.password()));
        user.setRole("USER");
        return AuthUserDTO.from(users.saveAndFlush(user));
    }

    @Transactional(readOnly = true)
    public AuthUserDTO me(Long userId) {
        return AuthUserDTO.from(users.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("Account no longer exists")));
    }

    @Transactional
    public void changePassword(Long userId, ChangePasswordRequest request) {
        PasswordPolicy.validate(request.newPassword());
        User user = users.findByIdForUpdate(userId)
                .orElseThrow(() -> new UserNotFoundException("Account no longer exists"));
        if (request.currentPassword().getBytes(StandardCharsets.UTF_8).length > 72
                || !encoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new BadRequestException("Current password is incorrect");
        }
        if (encoder.matches(request.newPassword(), user.getPasswordHash())) {
            throw new BadRequestException("New password must differ from the current password");
        }
        user.setPasswordHash(encoder.encode(request.newPassword()));
        user.setCredentialVersion(user.getCredentialVersion() + 1);
    }
}
