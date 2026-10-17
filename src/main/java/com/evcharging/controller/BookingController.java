package com.evcharging.controller;

import java.util.List;

import org.springframework.web.bind.annotation.*;

import com.evcharging.dto.BookingRequestDTO;
import com.evcharging.dto.BookingResponseDTO;

import com.evcharging.service.BookingService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/booking")
public class BookingController{
	
	private final BookingService bookingService;
	
	public BookingController(BookingService bookingService) {
		
		this.bookingService = bookingService;
	}
	
	
	@PostMapping("/createBooking")
	public void createBooking( @Valid @RequestBody BookingRequestDTO request)
	{
		bookingService.createBooking(request.getUserId(), request.getStationId());
		
		System.out.println("Booking has been created successfully");
	}
	
	@GetMapping("/{id}")
	public BookingResponseDTO searchBooking(@PathVariable Long id)
	{
		return bookingService.getBookingById(id);
	}
	
	@GetMapping("/all")
	public List<BookingResponseDTO> getAllBookings()
	{
		return bookingService.getAllBookings();
	}
	
	@PutMapping("/cancel")
	public void cancelBooking(@RequestParam Long id)
	{
		bookingService.cancelBooking(id);
	}
	

}
