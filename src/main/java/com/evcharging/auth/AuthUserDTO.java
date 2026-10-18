package com.evcharging.auth;

import com.evcharging.entity.User;

public record AuthUserDTO(Long id, String name, String email, String role) {
    public static AuthUserDTO from(User user) {
        return new AuthUserDTO(user.getId(), user.getName(), user.getEmail(), user.getRole());
    }
}
