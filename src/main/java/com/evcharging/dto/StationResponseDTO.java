package com.evcharging.dto;

import com.evcharging.entity.ChargingStation;

public record StationResponseDTO(Long id, String stationName, String location, int totalSlots, int avaialableSlots) {
    public static StationResponseDTO from(ChargingStation station) {
        return new StationResponseDTO(station.getId(), station.getStationName(), station.getLocation(),
                station.getTotalSlots(), station.getAvaialableSlots());
    }
}
