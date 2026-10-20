package com.evcharging.controller;

import com.evcharging.dto.BookingRequestDTO;
import com.evcharging.dto.BookingResponseDTO;
import com.evcharging.service.BookingService;
import com.evcharging.security.CurrentUser;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/booking")
public class BookingController {
    private final BookingService bookingService;
    private final CurrentUser current;

    public BookingController(BookingService bookingService, CurrentUser current) {
        this.bookingService = bookingService;
        this.current = current;
    }

    @PostMapping("/createBooking")
    public ResponseEntity<BookingResponseDTO> createBooking(@Valid @RequestBody BookingRequestDTO request,
            @RequestHeader(value = "Idempotency-Key", required = false) String requestKey) {
        current.requireOwner(request.getUserId());
        BookingResponseDTO booking = bookingService.createBooking(request.getUserId(), request.getStationId(), requestKey);
        return ResponseEntity.created(URI.create("/booking/" + booking.getBookingId())).body(booking);
    }

    @GetMapping("/{id}")
    public BookingResponseDTO searchBooking(@PathVariable Long id) {
        var booking = bookingService.getBookingById(id);
        current.requireOwner(booking.getUserId());
        return booking;
    }

    @GetMapping("/all")
    public List<BookingResponseDTO> getAllBookings() {
        current.requireAdmin();
        return bookingService.getAllBookings();
    }

    @GetMapping("/user/{userId}")
    public List<BookingResponseDTO> getBookingsByUser(@PathVariable Long userId) {
        current.requireOwner(userId);
        return bookingService.getBookingsByUser(userId);
    }

    @GetMapping("/station/{stationId}")
    public List<BookingResponseDTO> getBookingsByStation(@PathVariable Long stationId) {
        current.requireAdmin();
        return bookingService.getBookingsByStation(stationId);
    }

    @PutMapping("/cancel")
    public void cancelBooking(@RequestParam Long id) {
        current.requireOwner(bookingService.getBookingById(id).getUserId());
        bookingService.cancelBooking(id);
    }
}
