package com.evcharging.service.impl;

import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.evcharging.dto.UserRequestDTO;
import com.evcharging.entity.User;
import com.evcharging.exception.BadRequestException;
import com.evcharging.exception.DomainConflictException;
import com.evcharging.exception.UserNotFoundException;
import com.evcharging.repository.BookingRepository;
import com.evcharging.repository.UserRepository;
import com.evcharging.service.UserService;

@Service
@Transactional(readOnly = true)
public class UserServiceImpl implements UserService {
    private final UserRepository userRepository;
    private final BookingRepository bookingRepository;

    public UserServiceImpl(UserRepository userRepository, BookingRepository bookingRepository) {
        this.userRepository = userRepository;
        this.bookingRepository = bookingRepository;
    }

    @Override
    @Transactional
    public void registerUser(User user) {
        normalize(user);
        if (userRepository.existsByEmailIgnoreCase(user.getEmail())) {
            throw new DomainConflictException("A user with this email already exists");
        }
        // Flush here so concurrent duplicates also meet the database unique constraint.
        userRepository.saveAndFlush(user);
    }

    @Override
    public User getUserById(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException("User not found with ID : " + id));
    }

    @Override
    public List<User> getAllUsers() {
        return userRepository.findAll();
    }

    @Override
    @Transactional
    public User updateUser(Long id, UserRequestDTO request) {
        User existing = userRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new UserNotFoundException("User not found with ID : " + id));
        User updated = request.toEntity();
        normalize(updated);
        if (userRepository.existsByEmailIgnoreCaseAndIdNot(updated.getEmail(), id)) {
            throw new DomainConflictException("A user with this email already exists");
        }
        existing.setName(updated.getName());
        existing.setEmail(updated.getEmail());
        return userRepository.saveAndFlush(existing);
    }

    @Override
    @Transactional
    public void deleteUser(Long id) {
        User user = userRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new UserNotFoundException("User not found with ID : " + id));
        if (bookingRepository.existsByUserId(id)) {
            throw new DomainConflictException("User has booking history and cannot be deleted");
        }
        userRepository.delete(user);
    }

    private void normalize(User user) {
        if (user.getName() == null || user.getName().isBlank() || user.getName().strip().length() > 100) {
            throw new BadRequestException("User name must contain between 1 and 100 characters");
        }
        if (user.getEmail() == null || user.getEmail().isBlank() || user.getEmail().strip().length() > 254) {
            throw new BadRequestException("User email must contain between 1 and 254 characters");
        }
        user.setName(user.getName().strip());
        user.setEmail(user.getEmail().strip().toLowerCase(Locale.ROOT));
    }
}
