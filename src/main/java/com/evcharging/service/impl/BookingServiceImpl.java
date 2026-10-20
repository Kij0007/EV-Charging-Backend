package com.evcharging.service.impl;

import com.evcharging.dto.BookingResponseDTO;
import com.evcharging.entity.Booking;
import com.evcharging.entity.ChargingStation;
import com.evcharging.entity.User;
import com.evcharging.exception.*;
import com.evcharging.repository.*;
import com.evcharging.service.BookingService;
import com.evcharging.service.InboxService;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class BookingServiceImpl implements BookingService {
    private final UserRepository userRepository;
    private final ChargingStationRepository stationRepository;
    private final BookingRepository bookingRepository;
    private final InboxService inbox;

    public BookingServiceImpl(UserRepository userRepository, ChargingStationRepository stationRepository,
                              BookingRepository bookingRepository, InboxService inbox) {
        this.userRepository = userRepository;
        this.stationRepository = stationRepository;
        this.bookingRepository = bookingRepository;
        this.inbox = inbox;
    }

    @Override
    @Transactional
    public BookingResponseDTO createBooking(Long userId, Long stationId, String requestKey) {
        validateId(userId);
        validateId(stationId);
        if (requestKey != null && !requestKey.matches("[A-Za-z0-9._:-]{1,64}")) {
            throw new BadRequestException("Idempotency-Key must contain 1 to 64 letters, digits, dots, underscores, colons or hyphens");
        }
        // All creates lock in the same order. Deletion cannot race the user FK insert.
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new UserNotFoundException("User not found with ID: " + userId));
        ChargingStation station = stationRepository.findByIdForUpdate(stationId)
                .orElseThrow(() -> new StationNotFoundException("Station not found with ID: " + stationId));
        if (requestKey != null) {
            var previous = bookingRepository.findByRequestKey(requestKey);
            if (previous.isPresent()) {
                Booking booking = previous.get();
                if (!booking.getUser().getId().equals(userId) || booking.getStation().getId() != stationId.longValue()) {
                    throw new DomainConflictException("Idempotency-Key has already been used for a different booking request");
                }
                return toDto(booking);
            }
        }
        if (station.getAvaialableSlots() <= 0) {
            throw new DomainConflictException("No slots available for this station");
        }
        Booking booking = new Booking(user, station);
        booking.setBookingStatus(Booking.CONFIRMED);
        // This release reserves capacity without collecting online payments.
        booking.setAmount(0);
        booking.setRequestKey(requestKey);
        station.setAvaialableSlots(station.getAvaialableSlots() - 1);
        // A persisted inbox entry is part of the same transaction as the reservation.
        Booking saved = bookingRepository.saveAndFlush(booking);
        inbox.bookingNotice(user, "Booking #" + saved.getId() + " confirmed at " + station.getStationName()
                + ". No online payment was collected.");
        return toDto(saved);
    }

    @Override
    public BookingResponseDTO getBookingById(Long bookingId) {
        validateId(bookingId);
        return toDto(requireBooking(bookingId));
    }

    @Override
    public List<BookingResponseDTO> getAllBookings() {
        return bookingRepository.findAll().stream().map(this::toDto).toList();
    }

    @Override
    public List<BookingResponseDTO> getBookingsByUser(Long userId) {
        validateId(userId);
        if (!userRepository.existsById(userId)) {
            throw new UserNotFoundException("User not found with ID: " + userId);
        }
        return bookingRepository.findByUserId(userId).stream().map(this::toDto).toList();
    }

    @Override
    public List<BookingResponseDTO> getBookingsByStation(Long stationId) {
        validateId(stationId);
        if (!stationRepository.existsById(stationId)) {
            throw new StationNotFoundException("Station not found with ID: " + stationId);
        }
        return bookingRepository.findByStationId(stationId).stream().map(this::toDto).toList();
    }

    @Override
    @Transactional
    public void cancelBooking(Long bookingId) {
        finishBooking(bookingId, Booking.CANCELLED);
    }

    @Override
    @Transactional
    public void completeBooking(Long bookingId) {
        finishBooking(bookingId, Booking.COMPLETED);
    }

    private void finishBooking(Long bookingId, String nextStatus) {
        validateId(bookingId);
        // Scalar lookup avoids caching stale booking status before waiting for the station lock.
        Long stationId = bookingRepository.findStationIdById(bookingId)
                .orElseThrow(() -> new BookingNotFoundException("Booking not found with ID: " + bookingId));
        ChargingStation station = stationRepository.findByIdForUpdate(stationId)
                .orElseThrow(() -> new StationNotFoundException("Station not found with ID: " + stationId));
        Booking booking = requireBooking(bookingId);
        if (nextStatus.equals(booking.getBookingStatus())) {
            return;
        }
        if (!Booking.CONFIRMED.equals(booking.getBookingStatus())) {
            throw new DomainConflictException("Only confirmed bookings can be cancelled or completed");
        }
        if (station.getAvaialableSlots() >= station.getTotalSlots()) {
            throw new DomainConflictException("Station capacity is inconsistent; review the station before cancelling");
        }
        booking.setBookingStatus(nextStatus);
        station.setAvaialableSlots(station.getAvaialableSlots() + 1);
        inbox.bookingNotice(booking.getUser(), "Booking #" + booking.getId() + " " + nextStatus.toLowerCase(java.util.Locale.ROOT)
                + ". Charger capacity has been released.");
        // Both managed entities are dirty-checked and committed in this transaction.
    }

    private Booking requireBooking(Long bookingId) {
        return bookingRepository.findById(bookingId)
                .orElseThrow(() -> new BookingNotFoundException("Booking not found with ID: " + bookingId));
    }

    private void validateId(Long id) {
        if (id == null || id <= 0) {
            throw new BadRequestException("IDs must be positive integers");
        }
    }

    private BookingResponseDTO toDto(Booking booking) {
        return new BookingResponseDTO(booking.getId(), booking.getUser().getId(), booking.getStation().getId(),
                booking.getBookingStatus(), booking.getAmount(), booking.getStation().getStationName(),
                booking.getStation().getLocation(), booking.getCreatedAt());
    }
}
