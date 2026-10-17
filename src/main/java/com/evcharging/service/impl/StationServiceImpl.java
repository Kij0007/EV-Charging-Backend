package com.evcharging.service.impl;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.evcharging.entity.ChargingStation;
import com.evcharging.repository.ChargingStationRepository;
import com.evcharging.service.StationService;

@Service
public class StationServiceImpl implements StationService {
	//dependency
	private  final ChargingStationRepository stationRepository;
	
	public StationServiceImpl(ChargingStationRepository station) {
		
		stationRepository = station;
	}
	

@Override
public void addStation(ChargingStation station) {
	
	stationRepository.save(station);
	
}

@Override
public ChargingStation getStationById(Long id) {
	
	Optional<ChargingStation> optional = stationRepository.findById(id);
	
	if(optional.isPresent())
	{
		return optional.get();
	}
	throw new RuntimeException("station does not exist : "+id);
}

@Override
public List<ChargingStation> getAllStations() {
	
	return stationRepository.findAll();
}

@Override
public boolean checkSlotAvailability(Long stationId) {
	
ChargingStation station = getStationById(stationId);

return station.getAvaialableSlots() > 0;
}

@Override
public void reduceAvailableSlot(Long stationId) {
	
	ChargingStation station = getStationById(stationId);
	
	int currentSlot = station.getAvaialableSlots();
	
	if(currentSlot <= 0)
	{
		throw new RuntimeException("No slots available");
	}
	
	station.setAvaialableSlots(currentSlot - 1);
	
	stationRepository.save(station);
	
	System.out.println("Slot Reduced successfully");
	
}

}
