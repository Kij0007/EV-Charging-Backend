package com.evcharging.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.evcharging.dto.UserRequestDTO;
import com.evcharging.entity.Booking;
import com.evcharging.entity.User;
import com.evcharging.exception.DomainConflictException;
import com.evcharging.exception.UserNotFoundException;
import com.evcharging.repository.BookingRepository;
import com.evcharging.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {
    @Mock private UserRepository users;
    @Mock private BookingRepository bookings;
    private UserServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new UserServiceImpl(users, bookings);
    }

    @Test
    void registrationNormalizesEmailBeforeCheckingAndSaving() {
        User user = user("  Ada  ", "  ADA@example.com ");
        service.registerUser(user);
        assertThat(user.getName()).isEqualTo("Ada");
        assertThat(user.getEmail()).isEqualTo("ada@example.com");
        verify(users).existsByEmailIgnoreCase("ada@example.com");
        verify(users).saveAndFlush(user);
    }

    @Test
    void duplicateEmailCannotBeRegistered() {
        when(users.existsByEmailIgnoreCase("ada@example.com")).thenReturn(true);
        assertThatThrownBy(() -> service.registerUser(user("Ada", "ADA@example.com")))
                .isInstanceOf(DomainConflictException.class);
        verify(users, never()).saveAndFlush(any());
    }

    @Test
    void missingUserIsAResourceError() {
        assertThatThrownBy(() -> service.getUserById(9L)).isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void updateLocksExistingUserAndPreservesIdentity() {
        User existing = user("Old", "old@example.com");
        existing.setId(4L);
        when(users.findByIdForUpdate(4L)).thenReturn(Optional.of(existing));
        when(users.saveAndFlush(existing)).thenReturn(existing);
        User result = service.updateUser(4L, new UserRequestDTO("Ada", "ADA@example.com"));
        assertThat(result.getId()).isEqualTo(4L);
        assertThat(result.getEmail()).isEqualTo("ada@example.com");
        verify(users).existsByEmailIgnoreCaseAndIdNot("ada@example.com", 4L);
    }

    @Test
    void updateRejectsAnotherUsersEmailBeforeMutating() {
        User existing = user("Old", "old@example.com");
        when(users.findByIdForUpdate(4L)).thenReturn(Optional.of(existing));
        when(users.existsByEmailIgnoreCaseAndIdNot("ada@example.com", 4L)).thenReturn(true);
        assertThatThrownBy(() -> service.updateUser(4L, new UserRequestDTO("Ada", "ada@example.com")))
                .isInstanceOf(DomainConflictException.class);
        assertThat(existing.getEmail()).isEqualTo("old@example.com");
        verify(users, never()).saveAndFlush(any());
    }

    @Test
    void deletionRefusesEvenHistoricalBookings() {
        User user = user("Ada", "ada@example.com");
        when(users.findByIdForUpdate(4L)).thenReturn(Optional.of(user));
        when(bookings.existsByUserId(4L)).thenReturn(true);
        assertThatThrownBy(() -> service.deleteUser(4L)).isInstanceOf(DomainConflictException.class);
        verify(users, never()).delete(any(User.class));
    }

    @Test
    void deletionWithoutBookingsSucceeds() {
        User user = user("Ada", "ada@example.com");
        when(users.findByIdForUpdate(4L)).thenReturn(Optional.of(user));
        service.deleteUser(4L);
        verify(users).delete(user);
    }

    @Test
    void newUserCanAddBookingWithoutUninitializedCollection() {
        User user = new User();
        Booking booking = new Booking();
        user.addBooking(booking);
        assertThat(user.getBookings()).containsExactly(booking);
        assertThat(booking.getUser()).isSameAs(user);
    }

    private static User user(String name, String email) {
        User user = new User();
        user.setName(name);
        user.setEmail(email);
        return user;
    }
}
