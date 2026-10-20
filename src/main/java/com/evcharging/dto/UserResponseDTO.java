package com.evcharging.dto;

import com.evcharging.entity.User;

public record UserResponseDTO(Long id, String name, String email) {
    public static UserResponseDTO from(User user) {
        return new UserResponseDTO(user.getId(), user.getName(), user.getEmail());
    }
}
