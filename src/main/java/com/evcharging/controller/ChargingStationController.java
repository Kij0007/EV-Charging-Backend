package com.evcharging.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.evcharging.dto.StationRequestDTO;
import com.evcharging.dto.StationResponseDTO;
import com.evcharging.dto.StationUpdateRequestDTO;
import com.evcharging.exception.DomainConflictException;
import com.evcharging.service.StationService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/station")
public class ChargingStationController {
    private final StationService stationService;

    public ChargingStationController(StationService stationService) {
        this.stationService = stationService;
    }

    @PostMapping("/reg")
    public String registerStation(@Valid @RequestBody StationRequestDTO request) {
        stationService.addStation(request.toEntity());
        return "Station added successfully";
    }

    @GetMapping("/all")
    public List<StationResponseDTO> getAllStation() {
        return stationService.getAllStations().stream().map(StationResponseDTO::from).toList();
    }

    @GetMapping("/{id}")
    public StationResponseDTO getStationById(@PathVariable Long id) {
        return StationResponseDTO.from(stationService.getStationById(id));
    }

    @GetMapping("/isAvailable/{id}")
    public String checkAvailabilitySlot(@PathVariable Long id) {
        if (stationService.checkSlotAvailability(id)) {
            return "Slot is available for this Station ";
        }
        throw new DomainConflictException("No slot available");
    }

    @PutMapping("/reduceSlot/{id}")
    public void reduceSlot(@PathVariable Long id) {
        stationService.reduceAvailableSlot(id);
    }

    @PutMapping("/{id}")
    public StationResponseDTO updateStation(@PathVariable Long id,
            @Valid @RequestBody StationUpdateRequestDTO request) {
        return StationResponseDTO.from(stationService.updateStation(id, request));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteStation(@PathVariable Long id) {
        stationService.deleteStation(id);
    }

    @GetMapping("/search")
    public List<StationResponseDTO> searchStations(@RequestParam String query) {
        return stationService.searchStations(query).stream().map(StationResponseDTO::from).toList();
    }
}
