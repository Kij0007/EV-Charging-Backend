package com.evcharging.controller;

import java.util.List;

import org.springframework.web.bind.annotation.*;

import com.evcharging.entity.ChargingStation;
import com.evcharging.service.StationService;

@RestController
@RequestMapping("/station")
public class ChargingStationController {
	
	
	private final StationService stationService;
	
	public ChargingStationController(StationService stationService) {
		
		this.stationService = stationService;
	}
	
	
	@PostMapping("/reg")
	public String registerStation(@RequestBody ChargingStation station)
	{
		
		stationService.addStation(station);
		return "Station added successfully";
	}
	
	@GetMapping("/all")
	public List<ChargingStation> getAllStation(){
		
		return stationService.getAllStations();
	}
	
	@GetMapping("/{id}")
	public ChargingStation getStationById(@PathVariable Long id)
	{
		return stationService.getStationById(id);
	}
	
	@GetMapping("/isAvailable/{id}")
	public String checkAvailabilitySlot(@PathVariable Long id)
	{
		boolean availability = stationService.checkSlotAvailability(id);
		
		if(availability)
		{
			return "Slot is available for this Station " ;
		}
		
		 throw new RuntimeException("No slot available ");
	}
	
	@PutMapping("/reduceSlot/{id}")
	public void reduceSlot(@PathVariable Long id)
	{
		stationService.reduceAvailableSlot(id);
		
		System.out.println("Slot has been reduced successfully");
	}

}
