package com.evcharging.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "Charging_Stations")

public class ChargingStation {
	
	
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id ;
	
	
	private String stationName;
	private String location;
	private int totalSlots;
	private int avaialableSlots;
	
	public ChargingStation()
	{
		
	}

	public long getId() {
		return id;
	}

	public void setId(long id) {
		this.id = id;
	}

	public String getStationName() {
		return stationName;
	}

	public void setStationName(String stationName) {
		this.stationName = stationName;
	}

	public String getLocation() {
		return location;
	}

	public void setLocation(String location) {
		this.location = location;
	}

	public int getTotalSlots() {
		return totalSlots;
	}

	public void setTotalSlots(int totalSlots) {
		this.totalSlots = totalSlots;
	}

	public int getAvaialableSlots() {
		return avaialableSlots;
	}

	public void setAvaialableSlots(int avaialableSlots) {
		this.avaialableSlots = avaialableSlots;
	}
	
	
	
	
	

}
