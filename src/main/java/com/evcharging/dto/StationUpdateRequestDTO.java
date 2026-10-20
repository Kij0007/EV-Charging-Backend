package com.evcharging.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// Availability is derived from occupied capacity during updates; clients cannot reset it.
public record StationUpdateRequestDTO(
        @NotBlank @Size(max = 150) String stationName,
        @NotBlank @Size(max = 255) String location,
        @NotNull @Min(1) Integer totalSlots) {

    public StationUpdateRequestDTO {
        stationName = stationName == null ? null : stationName.strip();
        location = location == null ? null : location.strip();
    }
}
