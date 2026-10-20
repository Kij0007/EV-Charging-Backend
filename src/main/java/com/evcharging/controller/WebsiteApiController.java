package com.evcharging.controller;

import com.evcharging.dto.*;
import com.evcharging.entity.User;
import com.evcharging.security.CurrentUser;
import com.evcharging.service.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class WebsiteApiController {
    private final CurrentUser current;
    private final BookingService bookings;
    private final StationService stations;
    private final UserService users;
    private final InboxService inbox;

    public WebsiteApiController(CurrentUser current, BookingService bookings, StationService stations,
                                UserService users, InboxService inbox) {
        this.current = current; this.bookings = bookings; this.stations = stations; this.users = users; this.inbox = inbox;
    }

    @GetMapping("/stations")
    public List<StationResponseDTO> stations(@RequestParam(required = false) String query) {
        return (query == null || query.isBlank() ? stations.getAllStations() : stations.searchStations(query))
                .stream().map(StationResponseDTO::from).toList();
    }
    @PostMapping("/stations")
    public ResponseEntity<StationResponseDTO> addStation(@Valid @RequestBody StationRequestDTO request) {
        current.requireAdmin();
        var entity = request.toEntity();
        stations.addStation(entity);
        return ResponseEntity.created(URI.create("/station/" + entity.getId())).body(StationResponseDTO.from(entity));
    }
    @PutMapping("/stations/{id}")
    public StationResponseDTO updateStation(@PathVariable Long id, @Valid @RequestBody StationUpdateRequestDTO request) {
        current.requireAdmin(); return StationResponseDTO.from(stations.updateStation(id, request));
    }
    @DeleteMapping("/stations/{id}")
    public ResponseEntity<Void> deleteStation(@PathVariable Long id) {
        current.requireAdmin(); stations.deleteStation(id); return ResponseEntity.noContent().build();
    }
    @GetMapping("/bookings")
    public List<BookingResponseDTO> bookings() {
        return current.isAdmin() ? bookings.getAllBookings() : bookings.getBookingsByUser(current.id());
    }
    @PostMapping("/bookings")
    public ResponseEntity<BookingResponseDTO> reserve(@Valid @RequestBody ReservationRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        var booking = bookings.createBooking(current.id(), request.stationId(), key);
        return ResponseEntity.created(URI.create("/booking/" + booking.getBookingId())).body(booking);
    }
    @PostMapping("/bookings/{id}/cancel")
    public BookingResponseDTO cancel(@PathVariable Long id) {
        current.requireOwner(bookings.getBookingById(id).getUserId());
        bookings.cancelBooking(id); return bookings.getBookingById(id);
    }
    @PostMapping("/bookings/{id}/complete")
    public BookingResponseDTO complete(@PathVariable Long id) {
        current.requireAdmin(); bookings.completeBooking(id); return bookings.getBookingById(id);
    }
    @GetMapping("/profile")
    public Account profile() { return account(users.getUserById(current.id())); }
    @PutMapping("/profile")
    public Account updateProfile(@Valid @RequestBody UserRequestDTO request) {
        return account(users.updateUser(current.id(), request));
    }
    @GetMapping("/admin/users")
    public List<Account> accounts() { current.requireAdmin(); return users.getAllUsers().stream().map(this::account).toList(); }
    @GetMapping("/notifications")
    public List<InboxService.Notice> notifications() { return inbox.list(current.id()); }
    @PutMapping("/notifications/{id}/read")
    public ResponseEntity<Void> markRead(@PathVariable Long id) {
        inbox.markRead(current.id(), id); return ResponseEntity.noContent().build();
    }
    private Account account(User user) { return new Account(user.getId(), user.getName(), user.getEmail(), user.getRole()); }
    public record ReservationRequest(@NotNull @Positive Long stationId) {}
    public record Account(Long id, String name, String email, String role) {}
}
