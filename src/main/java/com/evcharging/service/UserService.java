package com.evcharging.service;

import java.util.List;

import com.evcharging.dto.UserRequestDTO;
import com.evcharging.entity.User;

public interface UserService {
    void registerUser(User user);

    User getUserById(Long id);

    List<User> getAllUsers();

    User updateUser(Long id, UserRequestDTO request);

    void deleteUser(Long id);
}
