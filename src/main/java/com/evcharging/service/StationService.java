package com.evcharging.service;

import java.util.List;

import com.evcharging.entity.ChargingStation;

public interface StationService {
	
	void addStation(ChargingStation station);
	
	List<ChargingStation> getAllStations();
	
	ChargingStation getStationById(Long id);
	
	boolean checkSlotAvailability(Long stationId);
	
	void reduceAvailableSlot(Long stationId);

}
