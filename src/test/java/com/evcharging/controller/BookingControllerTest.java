package com.evcharging.controller;

import com.evcharging.dto.BookingResponseDTO;
import com.evcharging.exception.BookingNotFoundException;
import com.evcharging.exception.BadRequestException;
import com.evcharging.service.BookingService;
import com.evcharging.security.CurrentUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(BookingController.class)
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc(addFilters = false)
class BookingControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean BookingService bookings;
    @MockitoBean CurrentUser current;

    @Test
    void createsAndReturnsDtoLocationAndKey() throws Exception {
        when(bookings.createBooking(1L, 2L, "request-1"))
                .thenReturn(new BookingResponseDTO(3L, 1L, 2L, "Confirmed", 500));
        mvc.perform(post("/booking/createBooking").contentType("application/json")
                        .header("Idempotency-Key", "request-1")
                        .content("{\"userId\":1,\"stationId\":2,\"amount\":500}"))
                .andExpect(status().isCreated()).andExpect(header().string("Location", "/booking/3"))
                .andExpect(jsonPath("$.bookingId").value(3)).andExpect(jsonPath("$.amount").value(500));
    }

    @Test
    void rejectsNullAndNonpositiveIdsAndAmountBeforeCallingService() throws Exception {
        mvc.perform(post("/booking/createBooking").contentType("application/json")
                        .content("{\"userId\":0,\"stationId\":null,\"amount\":-1}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.userId").exists())
                .andExpect(jsonPath("$.fieldErrors.stationId").exists())
                .andExpect(jsonPath("$.fieldErrors.amount").exists());
        verifyNoInteractions(bookings);
    }

    @Test
    void missingBookingUsesConsistentError() throws Exception {
        when(bookings.getBookingById(99L)).thenThrow(new BookingNotFoundException("Booking not found"));
        mvc.perform(get("/booking/99")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.timestamp").exists()).andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.path").value("/booking/99"));
    }

    @Test
    void filtersCallCorrectServices() throws Exception {
        when(bookings.getBookingsByUser(1L)).thenReturn(List.of());
        when(bookings.getBookingsByStation(2L)).thenReturn(List.of());
        mvc.perform(get("/booking/user/1")).andExpect(status().isOk()).andExpect(content().json("[]"));
        mvc.perform(get("/booking/station/2")).andExpect(status().isOk()).andExpect(content().json("[]"));
        verify(bookings).getBookingsByUser(1L);
        verify(bookings).getBookingsByStation(2L);
    }

    @Test
    void cancellationRequiresIdAndPreservesExisting200() throws Exception {
        when(bookings.getBookingById(3L)).thenReturn(new BookingResponseDTO(3L,1L,2L,"Confirmed",0));
        mvc.perform(put("/booking/cancel")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_PARAMETER"));
        mvc.perform(put("/booking/cancel").param("id", "3")).andExpect(status().isOk());
        verify(bookings).cancelBooking(3L);
    }

    @Test
    void invalidKeyDomainValidationHas400() throws Exception {
        when(bookings.createBooking(1L, 2L, "bad key")).thenThrow(new BadRequestException("Invalid key"));
        mvc.perform(post("/booking/createBooking").contentType("application/json")
                        .header("Idempotency-Key", "bad key")
                        .content("{\"userId\":1,\"stationId\":2,\"amount\":500}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }
}
