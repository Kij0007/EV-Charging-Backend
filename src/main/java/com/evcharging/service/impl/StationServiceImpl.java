package com.evcharging.service.impl;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.evcharging.dto.StationUpdateRequestDTO;
import com.evcharging.entity.ChargingStation;
import com.evcharging.exception.BadRequestException;
import com.evcharging.exception.DomainConflictException;
import com.evcharging.exception.StationNotFoundException;
import com.evcharging.repository.BookingRepository;
import com.evcharging.repository.ChargingStationRepository;
import com.evcharging.service.StationService;

@Service
@Transactional(readOnly = true)
public class StationServiceImpl implements StationService {
    private final ChargingStationRepository stationRepository;
    private final BookingRepository bookingRepository;

    public StationServiceImpl(ChargingStationRepository stationRepository, BookingRepository bookingRepository) {
        this.stationRepository = stationRepository;
        this.bookingRepository = bookingRepository;
    }

    @Override
    @Transactional
    public void addStation(ChargingStation station) {
        normalizeDetails(station);
        validateInventory(station.getTotalSlots(), station.getAvaialableSlots());
        stationRepository.save(station);
    }

    @Override
    public ChargingStation getStationById(Long id) {
        return stationRepository.findById(id)
                .orElseThrow(() -> new StationNotFoundException("Station not found with ID : " + id));
    }

    @Override
    public List<ChargingStation> getAllStations() {
        return stationRepository.findAll();
    }

    @Override
    public boolean checkSlotAvailability(Long stationId) {
        return getStationById(stationId).getAvaialableSlots() > 0;
    }

    @Override
    @Transactional
    public void reduceAvailableSlot(Long stationId) {
        ChargingStation station = lockedStation(stationId);
        if (station.getAvaialableSlots() <= 0) {
            throw new DomainConflictException("No slots available");
        }
        station.setAvaialableSlots(station.getAvaialableSlots() - 1);
        stationRepository.save(station);
    }

    @Override
    @Transactional
    public ChargingStation updateStation(Long id, StationUpdateRequestDTO request) {
        ChargingStation station = lockedStation(id);
        if (request.totalSlots() == null || request.totalSlots() <= 0) {
            throw new BadRequestException("Total slots must be positive");
        }
        int occupied = station.getTotalSlots() - station.getAvaialableSlots();
        if (request.totalSlots() < occupied) {
            throw new DomainConflictException("Total slots cannot be less than occupied slots");
        }
        station.setStationName(request.stationName());
        station.setLocation(request.location());
        normalizeDetails(station);
        station.setTotalSlots(request.totalSlots());
        station.setAvaialableSlots(request.totalSlots() - occupied);
        return stationRepository.save(station);
    }

    @Override
    @Transactional
    public void deleteStation(Long id) {
        ChargingStation station = lockedStation(id);
        if (bookingRepository.existsByStationId(id)) {
            throw new DomainConflictException("Station has booking history and cannot be deleted");
        }
        stationRepository.delete(station);
    }

    @Override
    public List<ChargingStation> searchStations(String query) {
        if (query == null || query.isBlank() || query.strip().length() > 100) {
            throw new BadRequestException("Search query must contain between 1 and 100 characters");
        }
        String normalized = query.strip();
        return stationRepository.findByStationNameContainingIgnoreCaseOrLocationContainingIgnoreCase(
                normalized, normalized);
    }

    private ChargingStation lockedStation(Long id) {
        return stationRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new StationNotFoundException("Station not found with ID : " + id));
    }

    private void normalizeDetails(ChargingStation station) {
        if (station.getStationName() == null || station.getStationName().isBlank()
                || station.getStationName().strip().length() > 150) {
            throw new BadRequestException("Station name must contain between 1 and 150 characters");
        }
        if (station.getLocation() == null || station.getLocation().isBlank()
                || station.getLocation().strip().length() > 255) {
            throw new BadRequestException("Station location must contain between 1 and 255 characters");
        }
        station.setStationName(station.getStationName().strip());
        station.setLocation(station.getLocation().strip());
    }

    private void validateInventory(int total, int available) {
        if (total <= 0 || available < 0 || available > total) {
            throw new BadRequestException("Station slots must satisfy total > 0 and 0 <= available <= total");
        }
    }
}
