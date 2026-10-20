package com.evcharging.dto;

import java.util.Locale;

import com.evcharging.entity.User;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UserRequestDTO(
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Email @Size(max = 254) String email) {

    public UserRequestDTO {
        name = name == null ? null : name.strip();
        email = email == null ? null : email.strip().toLowerCase(Locale.ROOT);
    }

    public User toEntity() {
        User user = new User();
        user.setName(name);
        user.setEmail(email);
        return user;
    }
}
