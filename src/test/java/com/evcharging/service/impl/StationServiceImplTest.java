package com.evcharging.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.evcharging.dto.StationUpdateRequestDTO;
import com.evcharging.entity.ChargingStation;
import com.evcharging.exception.BadRequestException;
import com.evcharging.exception.DomainConflictException;
import com.evcharging.exception.StationNotFoundException;
import com.evcharging.repository.BookingRepository;
import com.evcharging.repository.ChargingStationRepository;

@ExtendWith(MockitoExtension.class)
class StationServiceImplTest {
    @Mock private ChargingStationRepository stations;
    @Mock private BookingRepository bookings;
    private StationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new StationServiceImpl(stations, bookings);
    }

    @ParameterizedTest
    @CsvSource({"0, 0", "2, -1", "2, 3"})
    void invalidInventoryCannotBeCreated(int total, int available) {
        assertThatThrownBy(() -> service.addStation(station(total, available)))
                .isInstanceOf(BadRequestException.class);
        verify(stations, never()).save(any());
    }

    @Test
    void reductionUsesLockedRow() {
        ChargingStation station = station(3, 1);
        when(stations.findByIdForUpdate(7L)).thenReturn(Optional.of(station));
        service.reduceAvailableSlot(7L);
        assertThat(station.getAvaialableSlots()).isZero();
        verify(stations, never()).findById(7L);
        verify(stations).save(station);
    }

    @Test
    void exhaustedStationRejectsReduction() {
        when(stations.findByIdForUpdate(7L)).thenReturn(Optional.of(station(3, 0)));
        assertThatThrownBy(() -> service.reduceAvailableSlot(7L)).isInstanceOf(DomainConflictException.class);
        verify(stations, never()).save(any());
    }

    @Test
    void capacityUpdatePreservesOccupiedSlots() {
        ChargingStation station = station(3, 1); // Two slots are occupied.
        when(stations.findByIdForUpdate(7L)).thenReturn(Optional.of(station));
        when(stations.save(station)).thenReturn(station);
        ChargingStation result = service.updateStation(7L, new StationUpdateRequestDTO("New", "Pune", 5));
        assertThat(result.getTotalSlots()).isEqualTo(5);
        assertThat(result.getAvaialableSlots()).isEqualTo(3);
        assertThat(result.getStationName()).isEqualTo("New");
    }

    @Test
    void capacityCannotBeReducedBelowOccupiedSlots() {
        ChargingStation station = station(3, 1);
        when(stations.findByIdForUpdate(7L)).thenReturn(Optional.of(station));
        assertThatThrownBy(() -> service.updateStation(7L, new StationUpdateRequestDTO("New", "Pune", 1)))
                .isInstanceOf(DomainConflictException.class);
        assertThat(station.getTotalSlots()).isEqualTo(3);
        verify(stations, never()).save(any());
    }

    @Test
    void deletionRefusesBookingHistory() {
        when(stations.findByIdForUpdate(7L)).thenReturn(Optional.of(station(3, 3)));
        when(bookings.existsByStationId(7L)).thenReturn(true);
        assertThatThrownBy(() -> service.deleteStation(7L)).isInstanceOf(DomainConflictException.class);
        verify(stations, never()).delete(any(ChargingStation.class));
    }

    @Test
    void deletionWithoutBookingsSucceeds() {
        ChargingStation station = station(3, 3);
        when(stations.findByIdForUpdate(7L)).thenReturn(Optional.of(station));
        service.deleteStation(7L);
        verify(stations).delete(station);
    }

    @Test
    void searchNormalizesQueryAndChecksBothNameAndLocation() {
        ChargingStation station = station(3, 3);
        when(stations.findByStationNameContainingIgnoreCaseOrLocationContainingIgnoreCase("Pune", "Pune"))
                .thenReturn(List.of(station));
        assertThat(service.searchStations(" Pune ")).containsExactly(station);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t"})
    void blankSearchIsRejected(String query) {
        assertThatThrownBy(() -> service.searchStations(query)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void oversizedSearchIsRejected() {
        assertThatThrownBy(() -> service.searchStations("a".repeat(101))).isInstanceOf(BadRequestException.class);
    }

    @Test
    void missingStationIsAResourceError() {
        assertThatThrownBy(() -> service.getStationById(99L)).isInstanceOf(StationNotFoundException.class);
    }

    private static ChargingStation station(int total, int available) {
        ChargingStation station = new ChargingStation();
        station.setStationName("Central");
        station.setLocation("Pune");
        station.setTotalSlots(total);
        station.setAvaialableSlots(available);
        return station;
    }
}
