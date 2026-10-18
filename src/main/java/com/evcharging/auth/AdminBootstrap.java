package com.evcharging.auth;

import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.evcharging.entity.User;
import com.evcharging.repository.UserRepository;

import jakarta.validation.Validator;

/** Explicit initial provisioning only: never promotes or replaces an existing account. */
@Component
public class AdminBootstrap implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final Validator validator;
    private final String email;
    private final String password;

    public AdminBootstrap(UserRepository users, PasswordEncoder encoder, Validator validator,
            @Value("${app.admin.email:}") String email,
            @Value("${app.admin.password:}") String password) {
        this.users = users;
        this.encoder = encoder;
        this.validator = validator;
        this.email = email;
        this.password = password;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments arguments) {
        if (email.isBlank() && password.isBlank()) { return; }
        RegisterRequest request = new RegisterRequest("Administrator", email, password);
        if (!validator.validate(request).isEmpty()) {
            throw new IllegalStateException("Admin bootstrap requires a valid email and a password of at least 12 characters");
        }
        PasswordPolicy.validate(password);
        String normalized = email.strip().toLowerCase(Locale.ROOT);
        if (users.existsByEmailIgnoreCase(normalized)) {
            log.info("Admin bootstrap skipped: account already exists; no role or password was changed");
            return;
        }
        User admin = new User();
        admin.setName("Administrator");
        admin.setEmail(normalized);
        admin.setPasswordHash(encoder.encode(password));
        admin.setRole("ADMIN");
        users.saveAndFlush(admin);
        log.info("Initial administrator provisioned; remove bootstrap credentials from the running environment");
    }
}
