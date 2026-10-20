package com.evcharging.auth;

import java.nio.charset.StandardCharsets;

import com.evcharging.exception.BadRequestException;

public final class PasswordPolicy {
    private PasswordPolicy() { }

    public static void validate(String password) {
        if (password == null || password.length() < 12 || password.length() > 128
                || password.isBlank() || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new BadRequestException("Password must contain at least 12 characters and at most 72 UTF-8 bytes");
        }
    }
}
