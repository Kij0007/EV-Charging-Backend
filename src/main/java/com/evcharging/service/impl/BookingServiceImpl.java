package com.evcharging.service.impl;

import com.evcharging.dto.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.evcharging.entity.Booking;
import com.evcharging.entity.ChargingStation;
import com.evcharging.entity.User;
import com.evcharging.exception.BookingNotFoundException;
import com.evcharging.repository.BookingRepository;
import com.evcharging.service.*;

@Service
public class BookingServiceImpl implements BookingService {

    // dependencies
    private final UserService userService;
    private final StationService stationService;
    private final PaymentService paymentService;
    private final NotificationService notificationService;
    private final BookingRepository bookingRepository;

    public BookingServiceImpl(BookingRepository bookingRepository,
                              UserService userService,
                              StationService stationService,
                              PaymentService paymentService,
                              NotificationService notificationService) {

        this.bookingRepository = bookingRepository;
        this.userService = userService;
        this.stationService = stationService;
        this.paymentService = paymentService;
        this.notificationService = notificationService;
    }

    @Override
    public Booking createBooking(Long userId, Long stationId) {

        User user = userService.getUserById(userId);
        ChargingStation station = stationService.getStationById(stationId);

        boolean checkSlot = stationService.checkSlotAvailability(stationId);
        if (!checkSlot) {
            throw new RuntimeException("Slot is not available for this Station");
        }

        boolean paymentStatus = paymentService.processPayment(500);
        if (!paymentStatus) {
            throw new RuntimeException("Payment has failed");
        }

        Booking booking = new Booking(user, station);

        booking.setBookingStatus("Confirmed");
        booking.setAmount(500);

        user.addBooking(booking);

        Booking savedBooking = bookingRepository.save(booking);

        stationService.reduceAvailableSlot(stationId);

        notificationService.sendBookingConfirmation("Booking confirmed successfully");

        System.out.println("Booking created successfully");

        return savedBooking;
    }

    @Override
    public BookingResponseDTO getBookingById(Long bookingId) {

        Optional<Booking> optional = bookingRepository.findById(bookingId);

        if (optional.isPresent()) {
            Booking b1 = optional.get();

            return new BookingResponseDTO(
                    b1.getId(),
                    b1.getUser().getId(),
                    b1.getStation().getId(),
                    b1.getBookingStatus(),
                    b1.getAmount()
            );
        }

        throw new BookingNotFoundException("Booking not found with this id: " + bookingId);
    }

    @Override
    public List<BookingResponseDTO> getAllBookings() {

        List<Booking> bookings = bookingRepository.findAll();
        List<BookingResponseDTO> response = new ArrayList<>();

        for (Booking b : bookings) {
            response.add(new BookingResponseDTO(
                    b.getId(),
                    b.getUser().getId(),
                    b.getStation().getId(),
                    b.getBookingStatus(),
                    b.getAmount()
            ));
        }

        return response;
    }

    @Override
    public void cancelBooking(Long bookingId) {

        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new BookingNotFoundException("Booking not found"));

        booking.setBookingStatus("Cancelled");
        
        ChargingStation station = booking.getStation();
        
        station.setAvaialableSlots(station.getAvaialableSlots() + 1);
        bookingRepository.save(booking);

        System.out.println("Booking has been cancelled");
    }
}