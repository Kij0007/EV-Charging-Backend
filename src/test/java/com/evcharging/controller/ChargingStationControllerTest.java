package com.evcharging.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.evcharging.entity.ChargingStation;
import com.evcharging.exception.StationNotFoundException;
import com.evcharging.global.exception.handler.GlobalExceptionHandler;
import com.evcharging.service.StationService;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class ChargingStationControllerTest {
    @Mock private StationService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ChargingStationController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new StringHttpMessageConverter(), new JacksonJsonHttpMessageConverter(JsonMapper.builder()
                        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build()))
                .build();
    }

    @Test
    void registrationRetainsExistingSuccessContract() throws Exception {
        mvc.perform(post("/station/reg").contentType(MediaType.APPLICATION_JSON)
                .content("{\"stationName\":\"Central\",\"location\":\"Pune\",\"totalSlots\":3,\"avaialableSlots\":3}"))
                .andExpect(status().isOk()).andExpect(content().string("Station added successfully"));
        verify(service).addStation(any(ChargingStation.class));
    }

    @Test
    void inconsistentInventoryIsRejectedBeforeService() throws Exception {
        mvc.perform(post("/station/reg").contentType(MediaType.APPLICATION_JSON)
                .content("{\"stationName\":\"Central\",\"location\":\"Pune\",\"totalSlots\":3,\"avaialableSlots\":4}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        verifyNoInteractions(service);
    }

    @Test
    void missingCapacityIsRejected() throws Exception {
        mvc.perform(post("/station/reg").contentType(MediaType.APPLICATION_JSON)
                .content("{\"stationName\":\"Central\",\"location\":\"Pune\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.totalSlots").exists());
        verifyNoInteractions(service);
    }

    @Test
    void responsePreservesExistingAvailableSlotsSpelling() throws Exception {
        when(service.getStationById(7L)).thenReturn(station());
        mvc.perform(get("/station/7")).andExpect(status().isOk())
                .andExpect(jsonPath("$.avaialableSlots").value(3))
                .andExpect(jsonPath("$.stationName").value("Central"));
    }

    @Test
    void missingStationReturns404() throws Exception {
        when(service.getStationById(7L)).thenThrow(new StationNotFoundException("Station not found"));
        mvc.perform(get("/station/7")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void exhaustedStationReturns409() throws Exception {
        mvc.perform(get("/station/isAvailable/7")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DOMAIN_CONFLICT"));
    }

    @Test
    void availableStationRetainsExistingSuccessText() throws Exception {
        when(service.checkSlotAvailability(7L)).thenReturn(true);
        mvc.perform(get("/station/isAvailable/7")).andExpect(status().isOk())
                .andExpect(content().string("Slot is available for this Station "));
    }

    @Test
    void updateCannotResetAvailableInventory() throws Exception {
        mvc.perform(put("/station/7").contentType(MediaType.APPLICATION_JSON)
                .content("{\"stationName\":\"Central\",\"location\":\"Pune\",\"totalSlots\":3,\"avaialableSlots\":3}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_JSON"));
        verifyNoInteractions(service);
    }

    @Test
    void searchReturnsMatchingDtos() throws Exception {
        when(service.searchStations("Pune")).thenReturn(List.of(station()));
        mvc.perform(get("/station/search").param("query", "Pune")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].location").value("Pune"));
    }

    @Test
    void deletionReturns204() throws Exception {
        mvc.perform(delete("/station/7")).andExpect(status().isNoContent());
        verify(service).deleteStation(7L);
    }

    private static ChargingStation station() {
        ChargingStation station = new ChargingStation();
        station.setId(7L);
        station.setStationName("Central");
        station.setLocation("Pune");
        station.setTotalSlots(3);
        station.setAvaialableSlots(3);
        return station;
    }
}
