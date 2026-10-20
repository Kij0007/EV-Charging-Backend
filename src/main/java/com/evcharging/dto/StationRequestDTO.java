package com.evcharging.dto;

import com.evcharging.entity.ChargingStation;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record StationRequestDTO(
        @NotBlank @Size(max = 150) String stationName,
        @NotBlank @Size(max = 255) String location,
        @NotNull @Min(1) Integer totalSlots,
        @NotNull @Min(0) Integer avaialableSlots) {

    public StationRequestDTO {
        stationName = stationName == null ? null : stationName.strip();
        location = location == null ? null : location.strip();
    }

    @AssertTrue(message = "Available slots must not exceed total slots")
    public boolean isInventoryValid() {
        return totalSlots == null || avaialableSlots == null || avaialableSlots <= totalSlots;
    }

    public ChargingStation toEntity() {
        ChargingStation station = new ChargingStation();
        station.setStationName(stationName);
        station.setLocation(location);
        station.setTotalSlots(totalSlots);
        station.setAvaialableSlots(avaialableSlots);
        return station;
    }
}
