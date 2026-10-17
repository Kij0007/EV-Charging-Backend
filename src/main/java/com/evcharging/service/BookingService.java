package com.evcharging.service;

import java.util.List;

import com.evcharging.dto.BookingResponseDTO;
import com.evcharging.entity.Booking;



public interface BookingService {
	
	Booking createBooking(Long userId, Long stationId);
	
	BookingResponseDTO getBookingById(Long bookingId);
	
	List<BookingResponseDTO> getAllBookings();
	
	void cancelBooking(Long bookingId);

}
