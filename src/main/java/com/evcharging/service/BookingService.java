package com.evcharging.service;

import com.evcharging.dto.BookingResponseDTO;
import java.util.List;

public interface BookingService {
    BookingResponseDTO createBooking(Long userId, Long stationId, String requestKey);
    BookingResponseDTO getBookingById(Long bookingId);
    List<BookingResponseDTO> getAllBookings();
    List<BookingResponseDTO> getBookingsByUser(Long userId);
    List<BookingResponseDTO> getBookingsByStation(Long stationId);
    void cancelBooking(Long bookingId);
    void completeBooking(Long bookingId);
}
